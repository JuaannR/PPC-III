package practica3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;

/*
  Pasarela HTTP del cliente-broker (solo lectura).
    /index.html          pagina con los puntos de acceso al servicio
    /mostrar_datos.html  ultimos datos recibidos de las estaciones, en HTML
  Usa el servidor HTTP incluido en el JDK. Cada peticion se atiende en un hilo de un pool.
 */
public class PasarelaHttp {

    public static final int PUERTO = 8080;

    public static void iniciar() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress(PUERTO), 0);
            servidor.createContext("/index.html", PasarelaHttp::paginaIndex);
            servidor.createContext("/mostrar_datos.html", PasarelaHttp::paginaDatos);
            ApiRest.registrar(servidor);
            servidor.setExecutor(Executors.newFixedThreadPool(4)); // hasta 4 peticiones a la vez
            servidor.start();
            System.out.println("[HTTP] Escuchando en http://localhost:" + PUERTO + "/index.html");
        } catch (IOException e) {
            // Si el puerto esta ocupado no tiramos el cliente: la consola sigue funcionando
            System.out.println("[ERROR] HTTP no arranca en el puerto " + PUERTO + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // /index.html
    // ---------------------------------------------------------------------
    private static void paginaIndex(HttpExchange ex) throws IOException {
        StringBuilder b = new StringBuilder();

        b.append("<h2>Acceso web (HTML, solo lectura)</h2><ul>");
        b.append(enlace("/mostrar_datos.html", "Ultimos datos de las estaciones en tablas"));
        b.append("</ul>");

        b.append("<h2>API REST (JSON, lectura y escritura)</h2><ul>");
        b.append(enlace("/apirest/muestra_valores", "Ultimos valores de todas las estaciones"));
        b.append("</ul>");

        // Aqui iran despues MQTT y el correo
        enviarHtml(ex, pagina("Calidad del aire - Puntos de acceso", b.toString(), false));
    }

    // Una linea de lista con un enlace (la URL se muestra completa) y su
    // descripcion
    private static String enlace(String ruta, String descripcion) {
        return "<li><a href=\"" + esc(ruta) + "\">http://localhost:" + PUERTO + esc(ruta)
                + "</a> &ndash; " + esc(descripcion) + "</li>";
    }

    // ---------------------------------------------------------------------
    // /mostrar_datos.html
    // ---------------------------------------------------------------------
    private static void paginaDatos(HttpExchange ex) throws IOException {
        SimpleDateFormat fecha = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss"); // uno por peticion: no es thread-safe
        long ahora = System.currentTimeMillis();

        StringBuilder b = new StringBuilder();
        b.append("<p><a href=\"/index.html\">&larr; Puntos de acceso</a> &middot; se actualiza cada 5 s</p>");

        if (EstadoEstaciones.todos().isEmpty()) {
            b.append("<p>Aun no se ha recibido ninguna trama de las estaciones.</p>");
        }

        for (Dato d : EstadoEstaciones.todos()) {
            MensajeDistribucion m = d.mensaje;
            b.append("<h2>Estacion ").append(m.getIdServidor()).append("</h2>");
            b.append("<p>Formato de transmision: <b>").append(esc(d.formato)).append("</b>")
                    .append(" &middot; medido: ").append(fecha.format(new Date(m.getTimestamp())))
                    .append(" &middot; recibido hace ").append((ahora - d.recibido) / 1000).append(" s</p>");
            b.append("<table><tr><th>Variable</th><th>Valor</th><th>Unidad</th></tr>");
            for (Medicion x : m.getMediciones()) {
                b.append("<tr><td>").append(esc(x.getNombre())).append("</td><td>").append(x.getValor())
                        .append("</td><td>").append(esc(x.getUnidad())).append("</td></tr>");
            }
            b.append("</table>");
        }
        enviarHtml(ex, pagina("Calidad del aire - Ultimos datos", b.toString(), true));
    }

    // ---------------------------------------------------------------------
    // Utilidades
    // ---------------------------------------------------------------------

    // Envuelve el contenido en una pagina HTML completa. Si refresco es true, el
    // navegador la recarga solo cada 5 segundos.
    private static String pagina(String titulo, String cuerpo, boolean refresco) {
        return "<!DOCTYPE html>\n<html lang=\"es\"><head><meta charset=\"UTF-8\">"
                + (refresco ? "<meta http-equiv=\"refresh\" content=\"5\">" : "")
                + "<title>" + esc(titulo) + "</title>"
                + "<style>body{font-family:sans-serif;max-width:50em;margin:2em auto}"
                + "table{border-collapse:collapse}th,td{border:1px solid #bbb;padding:.3em .8em}"
                + "th{background:#eee}</style></head>"
                + "<body><h1>" + esc(titulo) + "</h1>" + cuerpo + "</body></html>";
    }

    // Responde 200 con el HTML. Este servicio es de solo lectura: solo se admite
    // GET.
    private static void enviarHtml(HttpExchange ex, String html) throws IOException {
        if (!ex.getRequestMethod().equals("GET")) {
            ex.getResponseHeaders().set("Allow", "GET");
            ex.sendResponseHeaders(405, -1); // -1 = sin cuerpo
            ex.close();
            return;
        }
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/html; charset=UTF-8");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    // Escapa los caracteres especiales de HTML
    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}