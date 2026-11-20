package practica3;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsExchange;
import java.io.FileInputStream;
import java.security.KeyStore;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.Executors;

public class PasarelaHttp {

    public static final int PUERTO = 8080;
    public static final int PUERTO_HTTPS = 8443;

    // Almacen de claves del servidor (el de la practica 1: clave privada +
    // certificado
    // de "localhost" firmado por nuestra CA)
    private static final String KEYSTORE_FILE = "cert/servidor.ks";
    private static final char[] KEYSTORE_PWD = "alumno".toCharArray();

    public static void iniciar() {
        // --- HTTP (puerto 8080) ---
        try {
            HttpServer http = HttpServer.create(new InetSocketAddress(PUERTO), 0);
            registrar(http);
            http.start();
            System.out.println("[HTTP] Escuchando en http://localhost:" + PUERTO + "/index.html");
        } catch (IOException e) {
            // Si el puerto esta ocupado no tiramos el cliente: la consola sigue funcionando
            System.out.println("[ERROR] HTTP no arranca en el puerto " + PUERTO + ": " + e.getMessage());
        }

        // --- HTTPS (puerto 8443): mismos contenidos, cifrados con TLS ---
        try {
            HttpsServer https = HttpsServer.create(new InetSocketAddress(PUERTO_HTTPS), 0);
            https.setHttpsConfigurator(new HttpsConfigurator(crearContextoSsl()));
            registrar(https);
            https.start();
            System.out.println("[HTTPS] Escuchando en https://localhost:" + PUERTO_HTTPS + "/index.html");
        } catch (Exception e) {
            System.out.println("[ERROR] HTTPS no arranca en el puerto " + PUERTO_HTTPS + ": " + e.getMessage());
        }
    }

    // Registra las URLs y el pool de hilos. Sirve para HttpServer y para
    // HttpsServer
    // (el segundo es un caso particular del primero), asi no se duplica nada.
    private static void registrar(HttpServer servidor) {
        servidor.createContext("/index.html", PasarelaHttp::paginaIndex);
        servidor.createContext("/mostrar_datos.html", PasarelaHttp::paginaDatos);
        ApiRest.registrar(servidor);
        servidor.setExecutor(Executors.newFixedThreadPool(4)); // hasta 4 peticiones a la vez
    }

    // Contexto SSL con el certificado del servidor (autenticacion del servidor)
    private static SSLContext crearContextoSsl() throws Exception {
        KeyStore ks = KeyStore.getInstance("JKS");
        try (FileInputStream fis = new FileInputStream(KEYSTORE_FILE)) {
            ks.load(fis, KEYSTORE_PWD);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, KEYSTORE_PWD);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null); // sin TrustManager: no se pide certificado al cliente
        return ctx;
    }

    // ---------------------------------------------------------------------
    // /index.html
    // ---------------------------------------------------------------------
    private static void paginaIndex(HttpExchange ex) throws IOException {
        // Los enlaces se construyen con el mismo protocolo y puerto con que se ha
        // pedido
        // esta pagina, para que al navegar por HTTPS no se vuelva a HTTP sin querer.
        boolean seguro = ex instanceof HttpsExchange;
        String base = seguro ? "https://localhost:" + PUERTO_HTTPS : "http://localhost:" + PUERTO;

        StringBuilder b = new StringBuilder();

        b.append("<h2>Acceso web (HTML, solo lectura)</h2><ul>");
        b.append(enlace(base, "/mostrar_datos.html", "Ultimos datos de las estaciones en tablas"));
        b.append("</ul>");

        b.append("<h2>API REST (JSON, lectura y escritura)</h2><ul>");
        b.append(enlace(base, "/apirest/muestra_valores", "Ultimos valores de todas las estaciones"));
        b.append("</ul>");

        // Enlace a la misma pagina por el otro protocolo
        String otraBase = seguro ? "http://localhost:" + PUERTO : "https://localhost:" + PUERTO_HTTPS;
        b.append(seguro ? "<h2>Acceso sin cifrar (HTTP)</h2><ul>" : "<h2>Acceso seguro (HTTPS)</h2><ul>");
        b.append(enlace(otraBase, "/index.html", seguro ? "el mismo servicio sin cifrar"
                : "el mismo servicio (HTML y API REST) cifrado con TLS"));
        b.append("</ul>");
        // Aqui iran despues MQTT y el correo
        enviarHtml(ex, pagina("Calidad del aire - Puntos de acceso", b.toString(), false));
    }

    // Una linea de lista con un enlace (la URL se muestra completa) y su
    // descripcion
    private static String enlace(String base, String ruta, String descripcion) {
        return "<li><a href=\"" + esc(base + ruta) + "\">" + esc(base + ruta)
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