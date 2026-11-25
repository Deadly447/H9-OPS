package io.github.deadly447.h9ops.aprs;

/** What one APRS packet says. Missing numbers are NaN; missing text is null. */
public final class AprsPacket {
    public enum Type { POSITION, MIC_E, OBJECT, ITEM, STATUS, MESSAGE, WEATHER, TELEMETRY, OTHER }

    public Type type = Type.OTHER;
    /** Station the packet is about: the sender, or the object/item name. */
    public String name;
    /** Station that sent it (differs from name for objects and items). */
    public String sender;
    public String dest;
    public String path;
    public String heardVia;      // null = heard direct
    public boolean thirdParty;   // came wrapped in a } packet (relayed by an iGate)
    public String gatedBy;       // third-party: the station that put it on the air (RIDGE)
    public boolean fromInternet; // third-party from APRS-IS (TCPIP in the inner path)
    /** The packet's content without third-party wrappers: copies by other routes compare equal. */
    public String content;

    public double lat = Double.NaN, lon = Double.NaN;
    public int ambiguity;        // 0-4 digits blanked
    public char symTable = '/', symCode = ' ';
    public double courseDeg = Double.NaN;
    public double speedKnots = Double.NaN;
    public double altitudeM = Double.NaN;
    public String comment;
    /** A position a station sent us inside a message (PositionShare), not a beacon. */
    public boolean shared;
    /** Rounded shared positions: the real spot is somewhere within this many metres. */
    public double radiusM = Double.NaN;
    /** An object this phone placed (shown from what it sends: the radio doesn't hear itself). */
    public boolean mine;

    public boolean killed;       // object/item deleted
    public String status;
    public String msgTo, msgText, msgId;
    public String miceStatus;    // Mic-E message, e.g. "En Route"
    public Weather wx;
    public String telemetry;
    /** For PARM./UNIT./EQNS./BITS.: the station they define (the message's addressee). */
    public String telemetryFor;
    public String raw;           // TNC2 text of the frame

    /** How it reached us: "direct", "via RIDGE", "internet, gated by RIDGE". */
    public String route() {
        String rf = heardVia == null ? "direct" : "via " + heardVia;
        if (!thirdParty) return rf;
        String r = (fromInternet ? "internet, gated by " : "gated by ") + gatedBy;
        return heardVia == null ? r : r + ", " + rf;
    }

    public boolean hasPosition() {
        return !Double.isNaN(lat) && !Double.isNaN(lon);
    }

    public static final class Weather {
        public double windDir = Double.NaN, windMph = Double.NaN, gustMph = Double.NaN;
        public double tempF = Double.NaN, rain1hIn = Double.NaN, rain24hIn = Double.NaN;
        public double rainMidnightIn = Double.NaN, humidity = Double.NaN, pressureMb = Double.NaN;

        public String summary() {
            StringBuilder sb = new StringBuilder();
            if (!Double.isNaN(tempF)) sb.append(Math.round(tempF)).append("°F ");
            if (!Double.isNaN(windMph)) {
                sb.append("wind ");
                if (!Double.isNaN(windDir)) sb.append(Format.compass(windDir)).append(' ');
                sb.append(Math.round(windMph)).append(" mph ");
            }
            if (!Double.isNaN(gustMph)) sb.append("gust ").append(Math.round(gustMph)).append(" ");
            if (!Double.isNaN(humidity)) sb.append(Math.round(humidity)).append("% RH ");
            if (!Double.isNaN(pressureMb)) sb.append(String.format("%.1f mb ", pressureMb));
            if (!Double.isNaN(rain24hIn)) sb.append(String.format("rain24h %.2f\" ", rain24hIn));
            return sb.toString().trim();
        }
    }
}
