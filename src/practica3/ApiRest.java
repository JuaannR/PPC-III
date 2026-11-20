package practica3;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/*
  API REST del cliente-broker. Siempre responde JSON (nunca HTML).
  Se cuelga del mismo HttpServer que la pasarela HTTP (puerto 8080).
 */
public class ApiRest {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // Registra los endpoints en el servidor HTTP que ya existe
    public static void registrar(HttpServer servidor) {
        servidor.createContext("/apirest", ApiRest::noEncontrado); // cualquier otra ruta
        servidor.createContext("/apirest/muestra_valores", ApiRest::muestraValores);
        servidor.createContext("/apirest/cambia_parametro", ApiRest::cambiaParametro);
    }

    // Cualquier otra URL bajo /apirest: 404 en JSON (la API nunca devuelve HTML)
    private static void noEncontrado(HttpExchange ex) throws IOException {
        enviarJson(ex, 404, error("Endpoint desconocido: " + ex.getRequestURI().getPath()
                + ". Disponibles: /apirest/muestra_valores y /apirest/cambia_parametro"));
    }

    // GET /apirest/muestra_valores -> ultimos datos de todas las estaciones
    private static void muestraValores(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            ex.getResponseHeaders().set("Allow", "GET");
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        long ahora = System.currentTimeMillis();
        JsonArray estaciones = new JsonArray();
        for (Dato d : EstadoEstaciones.todos()) {
            JsonObject est = new JsonObject();
            est.addProperty("srv", d.mensaje.getIdServidor());
            est.addProperty("formato", d.formato);
            est.addProperty("timestamp", d.mensaje.getTimestamp());
            est.addProperty("recibido_hace_s", (ahora - d.recibido) / 1000);
            JsonArray meds = new JsonArray();
            for (Medicion m : d.mensaje.getMediciones()) {
                JsonObject o = new JsonObject();
                o.addProperty("nombre", m.getNombre());
                o.addProperty("valor", m.getValor());
                o.addProperty("unidad", m.getUnidad());
                meds.add(o);
            }
            est.add("mediciones", meds);
            estaciones.add(est);
        }
        JsonObject raiz = new JsonObject();
        raiz.add("estaciones", estaciones);
        enviarJson(ex, 200, raiz);
    }

    // GET /apirest/cambia_parametro?srv=1&freq=2
    // Parametros: srv (obligatorio) y UNO de estos:
    // freq=<segundos> | formato=XML|JSON | envio=ON|OFF |
    // var=<variable>&unidad=<unidad>
    private static void cambiaParametro(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            ex.getResponseHeaders().set("Allow", "GET");
            ex.sendResponseHeaders(405, -1);
            ex.close();
            return;
        }
        Map<String, String> p = parametros(ex);

        // 1) Validar la peticion
        int srv;
        try {
            srv = Integer.parseInt(p.getOrDefault("srv", ""));
        } catch (NumberFormatException e) {
            enviarJson(ex, 400, error("Falta el parametro srv o no es un numero"));
            return;
        }

        if (srv < 1 || srv > 5000) {
            enviarJson(ex, 400, error("Identificador de servidor no valido: " + srv));
            return;
        }

        int cuantos = 0;
        for (String clave : new String[] { "freq", "formato", "envio", "unidad" }) {
            if (p.containsKey(clave)) {
                cuantos++;
            }
        }
        if (cuantos > 1) {
            enviarJson(ex, 400, error("Indica un solo parametro por peticion (freq, formato, envio o var+unidad)"));
            return;
        }

        // 2) Traducir la URL a una orden de control (operacion + parametro)
        String operacion;
        String parametro;
        if (p.containsKey("freq")) {
            operacion = "FRECUENCIA";
            parametro = p.get("freq");
        } else if (p.containsKey("formato")) {
            operacion = "FORMATO";
            parametro = p.get("formato");
        } else if (p.containsKey("envio")) {
            operacion = "ENVIO";
            parametro = p.get("envio");
        } else if (p.containsKey("var") && p.containsKey("unidad")) {
            operacion = "UNIDADES";
            parametro = p.get("var") + ":" + p.get("unidad");
        } else {
            enviarJson(ex, 400, error("Indica freq, formato, envio o var+unidad"));
            return;
        }

        // 3) Mandar la orden por UDP al servidor (el mismo codigo que usa la consola)
        try {
            if (!ControlServidores.ping(srv)) {
                enviarJson(ex, 404, error("El servidor " + srv + " no esta activo o no existe"));
                return;
            }
            RespuestaControl r = ControlServidores.enviar(srv, operacion, parametro);
            JsonObject o = new JsonObject();
            o.addProperty("srv", srv);
            o.addProperty("operacion", operacion);
            o.addProperty("parametro", parametro);
            o.addProperty("ok", r.isOk());
            o.addProperty("detalle", r.getDetalle());
            enviarJson(ex, r.isOk() ? 200 : 422, o); // 422 = el servidor rechazo la orden
        } catch (SocketTimeoutException e) {
            enviarJson(ex, 504, error("El servidor " + srv + " no ha respondido en 3 s"));
        } catch (Exception e) {
            enviarJson(ex, 500, error("Error interno: " + e.getMessage()));
        }
    }

    // Lee los parametros de la query (?a=1&b=2) en un mapa, ya decodificados
    private static Map<String, String> parametros(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q == null) {
            return m;
        }
        for (String par : q.split("&")) {
            String[] kv = par.split("=", 2);
            String clave = decodificar(kv[0]);
            String valor = kv.length > 1 ? decodificar(kv[1]) : "";
            m.put(clave, valor);
        }
        return m;
    }

    // Decodifica %XX y '+' de una URL (compatible con Java 8)
    private static String decodificar(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e); // UTF-8 siempre existe
        }
    }

    // {"ok": false, "error": "..."}
    private static JsonObject error(String mensaje) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", mensaje);
        return o;
    }

    // Escribe la respuesta JSON con el codigo HTTP indicado
    static void enviarJson(HttpExchange ex, int codigo, JsonElement json) throws IOException {
        byte[] bytes = GSON.toJson(json).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(codigo, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}