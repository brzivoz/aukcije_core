package rs.sud.eaukcija.rgz;

/** Synthetic narrow parcel; no captured/private RGZ boundary. */
public final class NativeCrsFixture {
    private NativeCrsFixture() { }
    public static String response(boolean nativeCrs, String ko, String parcel) {
        String ring = nativeCrs
                ? "[[500000,4900000],[500040,4900001],[500040,4900003],[500000,4900000]]"
                : "[[21.0000,44.2532],[21.0005,44.2532],[21.0005,44.2532],[21.0000,44.2532]]";
        return """
                {"type":"FeatureCollection","numberMatched":1,"numberReturned":1,
                 "crs":{"type":"name","properties":{"name":"EPSG:%s"}},
                 "features":[{"type":"Feature","id":"synthetic.narrow","properties":{
                   "cadmun_code":%s,"parcel_num":"%s","area":40,"source_projection":"GK7"},
                   "geometry":{"type":"MultiPolygon","coordinates":[[%s]]}}]}
                """.formatted(nativeCrs ? "25834" : "4326", ko, parcel, ring);
    }
}
