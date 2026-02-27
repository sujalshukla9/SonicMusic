import java.awt.BasicStroke;
import java.awt.geom.*;
import java.io.PrintWriter;

public class GenerateIcon {
    public static void main(String[] args) throws Exception {
        Path2D triangle = new Path2D.Double();
        triangle.setWindingRule(Path2D.WIND_EVEN_ODD);
        triangle.moveTo(54, 26);
        triangle.lineTo(79, 70);
        triangle.quadTo(81, 74, 78, 74);
        triangle.lineTo(30, 74);
        triangle.quadTo(27, 74, 29, 70);
        triangle.lineTo(54, 26);
        triangle.closePath();

        triangle.moveTo(54, 34);
        triangle.lineTo(36, 66);
        triangle.lineTo(72, 66);
        triangle.lineTo(54, 34);
        triangle.closePath();

        Path2D note = new Path2D.Double();
        note.moveTo(58, 42);
        note.lineTo(58, 60);
        note.quadTo(58, 66, 52, 66);
        note.quadTo(46, 66, 46, 60);
        note.quadTo(46, 54, 52, 54);
        note.lineTo(54, 54);
        note.lineTo(54, 42);
        note.lineTo(58, 42);
        note.closePath();

        BasicStroke stroke = new BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND);

        Area tArea = new Area(triangle);
        tArea.add(new Area(stroke.createStrokedShape(triangle)));

        Area nArea = new Area(note);
        nArea.add(new Area(stroke.createStrokedShape(note)));

        Area logo = new Area(tArea);
        logo.add(nArea);

        AffineTransform tx = AffineTransform.getTranslateInstance(54, 54);
        tx.scale(1.6, 1.6);
        tx.translate(-54, -54);
        logo.transform(tx);

        Area bg = new Area(new Ellipse2D.Double(2, 2, 104, 104)); // Circle fits exactly in 108x108
        bg.subtract(logo);

        PathIterator pi = bg.getPathIterator(null, 0.05);
        StringBuilder sb = new StringBuilder();
        double[] coords = new double[6];
        while (!pi.isDone()) {
            int type = pi.currentSegment(coords);
            switch (type) {
                case PathIterator.SEG_MOVETO: sb.append(String.format("M%.2f,%.2f ", coords[0], coords[1])); break;
                case PathIterator.SEG_LINETO: sb.append(String.format("L%.2f,%.2f ", coords[0], coords[1])); break;
                case PathIterator.SEG_QUADTO: sb.append(String.format("Q%.2f,%.2f %.2f,%.2f ", coords[0], coords[1], coords[2], coords[3])); break;
                case PathIterator.SEG_CUBICTO: sb.append(String.format("C%.2f,%.2f %.2f,%.2f %.2f,%.2f ", coords[0], coords[1], coords[2], coords[3], coords[4], coords[5])); break;
                case PathIterator.SEG_CLOSE: sb.append("Z "); break;
            }
            pi.next();
        }

        String pathData = sb.toString().replace(".00", "");

        String xml = "<vector xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                     "    android:width=\"24dp\"\n" +
                     "    android:height=\"24dp\"\n" +
                     "    android:viewportWidth=\"108\"\n" +
                     "    android:viewportHeight=\"108\">\n" +
                     "    <path\n" +
                     "        android:fillColor=\"#ffffff\"\n" +
                     "        android:pathData=\"" + pathData + "\" />\n" +
                     "</vector>";

        try (PrintWriter out = new PrintWriter("app/src/main/res/drawable/ic_notification.xml")) {
            out.println(xml);
        }
        System.out.println("Done! XML generated.");
    }
}
