package rs.sud.eaukcija.rgz;

/** Synthetic metadata only; no external schema imports or provider/personal fields. */
public final class RgzMetadataFixture {
    private RgzMetadataFixture() { }

    public static final String CAPABILITIES = """
            <wfs:WFS_Capabilities xmlns:wfs="http://www.opengis.net/wfs/2.0"
                xmlns:ows="http://www.opengis.net/ows/1.1" version="2.0.0" updateSequence="not-a-dataset-edition">
              <ows:OperationsMetadata><ows:Operation name="GetFeature"><ows:Parameter name="outputFormat">
                <ows:AllowedValues><ows:Value>application/json</ows:Value></ows:AllowedValues>
              </ows:Parameter></ows:Operation></ows:OperationsMetadata>
              <wfs:FeatureTypeList><wfs:FeatureType>
                <wfs:Name>dkp:dkp_parcels_weekly_only_utm</wfs:Name>
                <wfs:DefaultCRS>urn:ogc:def:crs:EPSG::25834</wfs:DefaultCRS>
              </wfs:FeatureType></wfs:FeatureTypeList>
            </wfs:WFS_Capabilities>
            """;

    public static final String SCHEMA = """
            <xsd:schema xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:gml="http://www.opengis.net/gml/3.2">
              <xsd:complexType name="dkp_parcels_weekly_only_utmType"><xsd:sequence>
                <xsd:element name="cadmun_code" type="xsd:int"/>
                <xsd:element name="parcel_num" type="xsd:string"/>
                <xsd:element name="area" type="xsd:decimal"/>
                <xsd:element name="geom" type="gml:GeometryPropertyType"/>
                <xsd:element name="future_field" type="xsd:string"/>
              </xsd:sequence></xsd:complexType>
            </xsd:schema>
            """;
}
