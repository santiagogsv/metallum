import com.metallum.render.RenderScale;

public class RenderScaleSmoke {
    public static void main(String[] args) {
        for (String value : new String[]{"bad", "NaN", "Infinity", "0", "0.66", "1", "2"}) {
            if (RenderScale.parse(value) != 1) throw new AssertionError(value);
        }
        if (RenderScale.parse("0.85") != .85 || RenderScale.parse("0.67") != .67)
            throw new AssertionError("Valid render scale rejected");
        if (RenderScale.dimension(1708, .85) != 1451 || RenderScale.dimension(960, .85) != 816
                || RenderScale.dimension(1, .67) != 1) throw new AssertionError("Scaled dimensions");
        System.out.println("Render scale checks passed");
    }
}
