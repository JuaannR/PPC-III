package practica3;


// Foto inmutable de lo último que se sabe de una estación
public class Dato {
    public final MensajeDistribucion mensaje;
    public final String formato;
    public final long recibido;
    
    public Dato(MensajeDistribucion mensaje, String formato, long recibido) {
        this.mensaje = mensaje;
        this.formato = formato;
        this.recibido = recibido;
    }
}
