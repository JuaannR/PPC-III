package practica3;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Scanner;

public class Cliente {

    private static int leerOpcion(Scanner sc) {
        try {
            return Integer.parseInt(sc.nextLine().trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public static void main(String[] args) {
        // Puerto fijo comun de broadcast definido en la practica (5000)
        int puertoBroadcast = 5000;

        System.out.println("=== CLIENTE DE CALIDAD DEL AIRE INICIADO ===");

        // =========================================================================
        // HILO SECUNDARIO (RECEPTOR DE BROADCAST)
        // Se ejecuta en paralelo para escuchar de forma continua los datos que
        // envian todos los servidores sin bloquear la consola principal.
        // =========================================================================
        Thread hiloReceptor = new Thread(() -> {
            try {
                // Creamos el socket sin puerto primero para configurar el reuso de direcciones
                DatagramSocket socketEscucha = new DatagramSocket(null);
                socketEscucha.setReuseAddress(true);
                // Vinculamos al puerto broadcast (permite abrir multiples clientes a la vez)
                socketEscucha.bind(new InetSocketAddress(puertoBroadcast));

                byte[] buffer = new byte[8192];

                while (true) {
                    DatagramPacket paquete = new DatagramPacket(buffer, buffer.length);
                    socketEscucha.receive(paquete); // Bloqueante hasta que llega una trama UDP

                    try {
                        Trama t = Trama.decodificar(paquete.getData(), paquete.getLength());

                        MensajeDistribucion m;
                        if (t.getFormato().equals("JSON")) {
                            m = JsonSerializador.deserializar(t.getCuerpo());
                        } else if (t.getFormato().equals("XML")) {
                            m = XmlSerializador.deserializar(t.getCuerpo()); // valida contra el XSD
                        } else {
                            System.out.println("\n[DATOS " + t.getFormato() + "] formato no soportado");
                            continue;
                        }

                        EstadoEstaciones.actualizar(m, t.getFormato());
                        System.out.println("\n[DATOS " + t.getFormato() + "] " + m);
                        Archivos.anadir("Servidor" + m.getIdServidor(), "mediciones.txt",
                                t.getCuerpo().trim() + System.lineSeparator());
                    } catch (Exception e) {
                        System.out.println("[ERROR] Trama no valida: " + e.getMessage());
                    }

                }
            } catch (Exception e) {
                System.out.println("[ERROR] Receptor de broadcast: " + e.getMessage());
            }
        });
        hiloReceptor.setDaemon(true); // Permite que el hilo muera automaticamente al cerrar el cliente
        hiloReceptor.start();

        // =========================================================================
        // PASARELAS DEL BROKER (acceso remoto). Cada una arranca sus propios hilos.
        // =========================================================================
        PasarelaHttp.iniciar(); // HTTP: index.html y mostrar_datos.html

        // =========================================================================
        // HILO PRINCIPAL (PANEL DE CONTROL INTERACTIVO)
        // Gestiona la interaccion con el usuario por consola y el envio unicast.
        // =========================================================================
        Scanner scanner = new Scanner(System.in);

        while (true) {
            try {
                System.out.println("\n==================================");
                System.out.println("     PANEL DE CONTROL CLIENTE     ");
                System.out.println("==================================");
                System.out.print("Introduce el ID del servidor al que quieres mandar la orden (ej: 1, 2, 3...): ");

                int idServidor = scanner.nextInt();

                System.out.println("Comprobando conexion con el Servidor " + idServidor + "...");

                // =========================================================================
                // MECANISMO DE VERIFICACION (PING RAPIDO CON TIMEOUT):
                // En lugar de asumir que el servidor existe, enviamos un paquete ligero
                // ("PING")
                // al puerto de control especifico. Si responde, esta activo. Si no, salta la
                // excepcion y evitamos abrir el menu para un servidor apagado o inexistente.
                // =========================================================================
                if (!ControlServidores.ping(idServidor)) {
                    System.out.println("\n[AVISO] El Servidor " + idServidor
                            + " no esta activo o no existe. Imposible mandar ordenes.");
                    continue; // Vuelve arriba al menu principal de forma limpia
                }

                // =========================================================================
                // SUBMENU DE ACCIONES (EXIGIDAS EN EL BOLETIN)
                // Si el servidor existe, se despliegan las opciones de control.
                // =========================================================================
                System.out.println("\n--- ACCIONES PARA EL SERVIDOR " + idServidor + " ---");
                System.out.println("1. Cambiar formato (XML / JSON)");
                System.out.println("2. Cambiar frecuencia de transmision");
                System.out.println("3. Cambiar unidades de una variable");
                System.out.println("4. Activar / Desactivar envio de datos");
                System.out.print("Selecciona una accion (1-4): ");

                int accion = scanner.nextInt();
                scanner.nextLine(); // Limpiar el salto de linea pendiente

                String operacion;
                String parametro;

                switch (accion) {
                    case 1:
                        operacion = "FORMATO";
                        String actual = EstadoEstaciones.formato(idServidor);
                        if (actual != null) {
                            parametro = actual.equals("JSON") ? "XML" : "JSON";
                            System.out.println("Formato actual del servidor " + idServidor + ": " + actual
                                    + " -> se cambia a " + parametro);
                        } else {
                            System.out.print("Formato actual desconocido. Introduce el nuevo formato (XML o JSON): ");
                            parametro = scanner.nextLine();
                        }
                        break;
                    case 2:
                        System.out.print("Introduce la nueva frecuencia en segundos: ");
                        operacion = "FRECUENCIA";
                        parametro = scanner.nextLine();
                        break;
                    case 3:
                        Dato ultimoDato = EstadoEstaciones.get(idServidor);
                        if (ultimoDato == null) {
                            System.out.println("[AVISO] Aun no se han recibido datos del servidor " + idServidor
                                    + ". Espera a que llegue alguna trama.");
                            continue;
                        }
                        List<Medicion> meds = ultimoDato.mensaje.getMediciones();
                        System.out.println("Variables del servidor " + idServidor + ":");
                        for (int i = 0; i < meds.size(); i++) {
                            System.out.println("  " + (i + 1) + ". " + meds.get(i).getNombre()
                                    + " (" + meds.get(i).getUnidad() + ")");
                        }
                        System.out.print("Elige variable: ");
                        int v = leerOpcion(scanner);
                        if (v < 1 || v > meds.size()) {
                            System.out.println("[!] Variable no valida. Cancelando.");
                            continue;
                        }
                        Medicion elegida = meds.get(v - 1);

                        List<String> unidades = Unidades.compatibles(elegida.getUnidad());
                        if (unidades.size() < 2) {
                            System.out.println(
                                    "[AVISO] " + elegida.getNombre() + " solo admite " + elegida.getUnidad() + ".");
                            continue;
                        }
                        System.out.println("Unidades disponibles para " + elegida.getNombre() + ":");
                        for (int i = 0; i < unidades.size(); i++) {
                            System.out.println("  " + (i + 1) + ". " + unidades.get(i)
                                    + (unidades.get(i).equals(elegida.getUnidad()) ? " (actual)" : ""));
                        }
                        System.out.print("Elige unidad: ");
                        int u = leerOpcion(scanner);
                        if (u < 1 || u > unidades.size()) {
                            System.out.println("[!] Unidad no valida. Cancelando.");
                            continue;
                        }
                        operacion = "UNIDADES";
                        parametro = elegida.getNombre() + ":" + unidades.get(u - 1);
                        break;
                    case 4:
                        System.out.print("¿Activar (ON) o Desactivar (OFF)?: ");
                        operacion = "ENVIO";
                        parametro = scanner.nextLine();
                        break;
                    default:
                        System.out.println("[!] Opcion no valida. Cancelando envio.");
                        continue;
                }

                // =========================================================================
                // ENVIO DE LA ORDEN DE CONTROL POR UNICAST Y ESPERA DE ACK
                // =========================================================================
                try {
                    System.out.println("-> Enviando comando al Servidor " + idServidor
                            + " (Puerto " + (ControlServidores.PUERTO_BASE_CONTROL + idServidor) + ")");
                    RespuestaControl respuesta = ControlServidores.enviar(idServidor, operacion, parametro);
                    System.out.println("[RESPUESTA] " + respuesta);
                } catch (java.net.SocketTimeoutException e) {
                    System.out.println("[AVISO] El servidor " + idServidor
                            + " no ha respondido en 3 s. Puedes repetir la orden.");
                } catch (Exception e) {
                    e.printStackTrace();
                }

            } catch (Exception e) {
                // Control general de errores: Si se introduce una letra por error o se cruza
                // texto,
                // vaciamos por completo el buffer del scanner para evitar bucles infinitos.
                System.out.println("\n[ERROR] Entrada no valida o error inesperado. Vuelve a intentarlo.");
                scanner.nextLine();
            }
        }
    }
}