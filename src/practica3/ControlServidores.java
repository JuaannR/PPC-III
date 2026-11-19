package practica3;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/*
  Envio de ordenes de control por unicast a un servidor concreto.
  Cada llamada usa su propio socket, asi que es seguro llamarla desde varios hilos.
 */
public class ControlServidores {

    public static final int PUERTO_BASE_CONTROL = 6000;

    // PING rapido: true si el servidor responde en 1,5 s
    public static boolean ping(int id) throws IOException {
        try (DatagramSocket s = new DatagramSocket()) {
            byte[] b = "PING".getBytes();
            s.send(new DatagramPacket(b, b.length, InetAddress.getByName("127.0.0.1"),
                    PUERTO_BASE_CONTROL + id));
            s.setSoTimeout(1500);
            s.receive(new DatagramPacket(new byte[1024], 1024));
            return true;
        } catch (SocketTimeoutException e) {
            return false;
        }
    }

    // Envia la orden en el formato en que el servidor esta transmitiendo y devuelve
    // su respuesta. Lanza SocketTimeoutException si no contesta en 3 s.
    public static RespuestaControl enviar(int id, String operacion, String parametro) throws Exception {
        try (DatagramSocket s = new DatagramSocket()) {
            MensajeControl orden = new MensajeControl(id, operacion, parametro);

            // La orden se codifica en el formato en que el servidor esta enviando sus datos
            String formato = EstadoEstaciones.formato(id);
            if (formato == null) {
                formato = "JSON";
            }
            String cuerpo = formato.equals("XML") ? XmlSerializador.serializar(orden)
                    : JsonSerializador.serializar(orden);
            byte[] envio = new Trama(formato, "CONTROL", cuerpo).codificar();
            s.send(new DatagramPacket(envio, envio.length, InetAddress.getByName("127.0.0.1"),
                    PUERTO_BASE_CONTROL + id));

            // Espera de la respuesta exclusiva del servidor (ACK)
            s.setSoTimeout(3000);
            DatagramPacket paquete = new DatagramPacket(new byte[4096], 4096);
            s.receive(paquete);
            Trama tr = Trama.decodificar(paquete.getData(), paquete.getLength());
            RespuestaControl r = tr.getFormato().equals("XML")
                    ? XmlSerializador.deserializarRespuesta(tr.getCuerpo()) // valida contra respuesta.xsd
                    : JsonSerializador.deserializarRespuesta(tr.getCuerpo());

            if (r.isOk() && operacion.equals("FORMATO")) {
                EstadoEstaciones.setFormato(id, parametro.trim().toUpperCase());
            }
            Archivos.anadir("Servidor" + id, "respuestas.txt", tr.getCuerpo().trim());
            return r;
        }
    }
}