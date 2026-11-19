package practica3;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/*
  Ultimos valores recibidos de cada estacion. Lo escribe el hilo receptor de
  broadcast y lo leeran la consola y las pasarelas (HTTP, REST, MQTT, correo).
  Solo se guarda el ultimo valor de cada estacion.
 */

public class EstadoEstaciones {
    
    // Mapa seguro para varios hilos y ordenado por id de servidor
    private static final Map<Integer, Dato> ultimos = new ConcurrentSkipListMap<>();

    // Guarda la última trama recibida de una estación
    public static void actualizar(MensajeDistribucion m, String formato) {
        ultimos.put(m.getIdServidor(), new Dato(m, formato, System.currentTimeMillis()));
    }

    // Formato en que transmite la estación, o null si aún no se sabe
    public static String formato (int id) {
        Dato d = ultimos.get(id);
        return d == null ? null : d.formato;
    }

    // Cambia el formato conocido. Se usa tras una orden FORMATO confirmada
    public static void setFormato(int id, String formato) {
        Dato d = ultimos.get(id);
        if (d != null) {
            ultimos.put(id, new Dato(d.mensaje, formato, d.recibido));
        }
    }

    // Todas las estaciones conocidas, ordenadas por id
    public static List<Dato> todos() {
        return new ArrayList<>(ultimos.values());
    }

    // ultimo dato de la estación, o null si aún no ha enviado nada
    public static Dato get(int id) {
        return ultimos.get(id);
    }
}
