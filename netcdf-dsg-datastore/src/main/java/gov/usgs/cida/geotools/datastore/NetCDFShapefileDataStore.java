package gov.usgs.cida.geotools.datastore;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.*;
import org.geotools.data.*;
import org.geotools.data.shapefile.ShapefileAttributeReader;
import org.geotools.data.shapefile.ShapefileDataStore;
import org.geotools.feature.AttributeTypeBuilder;
import org.geotools.feature.SchemaException;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.feature.type.AttributeDescriptor;
import ucar.nc2.VariableSimpleIF;
import ucar.nc2.dataset.NetcdfDataset;
import ucar.nc2.ft.FeatureDataset;

/**
 *
 * @author tkunicki
 */
public class NetCDFShapefileDataStore extends ShapefileDataStore {
    
    static {
         NetcdfDataset.initNetcdfFileCache(32, 64, 600);
         NetcdfDataset.setUseNaNs(false);
    }
    
    public final static String VARIABLE_KEY = "variable";
    public final static String EXTRACTOR_KEY = "extractor";
     
    private final URL netCDFURL;
    private final String shapefileStationAttributeName;
    
    private Set<String> shapefileAttributeNames;
    private Set<String> netCDFAttributeNames;
    
    private VariableSimpleIF observationTimeVariable;

    public NetCDFShapefileDataStore(URI namespaceURI, URL netCDFURL, URL shapefileURL, String shapefileStationAttributeName) throws MalformedURLException, IOException {
        super(shapefileURL, namespaceURI, true, true, ShapefileDataStore.DEFAULT_STRING_CHARSET);
        
        this.netCDFURL = netCDFURL;
        
        this.shapefileStationAttributeName = shapefileStationAttributeName;
        
    }
    
    @Override
    protected List<AttributeDescriptor> readAttributes() throws IOException {
        List<AttributeDescriptor> shapefileAttributeDescriptors = super.readAttributes();
        
        shapefileAttributeNames = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        for (AttributeDescriptor attributeDescriptor : shapefileAttributeDescriptors) {
            shapefileAttributeNames.add(attributeDescriptor.getLocalName());
        }
        
        FeatureDataset featureDataset = null;
        List<AttributeDescriptor> attributeDescriptors = null;
        try {
            featureDataset = NetCDFUtil.acquireDataSet(netCDFURL);

            List<VariableSimpleIF> observationVariables = NetCDFUtil.getObservationVariables(featureDataset);
            observationTimeVariable = NetCDFUtil.getObservationTimeVariable(featureDataset);

    //        observationVariables.remove(observationTimeVariable);
            VariableSimpleIF toRemove = null;
            for (VariableSimpleIF variable : observationVariables) {
                if (variable.getFullName().equals(observationTimeVariable.getFullName())) {
                    toRemove = variable;
                }
            }
            if (toRemove != null) {
                observationVariables.remove(toRemove);
            }

            List<AttributeDescriptor> netCDFAttributeDescriptors = new ArrayList<AttributeDescriptor>(observationVariables.size());

            AttributeTypeBuilder atBuilder = new AttributeTypeBuilder();

            netCDFAttributeDescriptors.add(atBuilder.
                    userData(VARIABLE_KEY, observationTimeVariable).
                    userData(EXTRACTOR_KEY, new NetCDFPointFeatureExtractor.TimeStamp()).
                    binding(Date.class).
                    buildDescriptor(observationTimeVariable.getShortName()));

            for (int observationVariableIndex = 0, observationVariableCount = observationVariables.size();
                    observationVariableIndex < observationVariableCount; ++observationVariableIndex) {
                VariableSimpleIF observationVariable = observationVariables.get(observationVariableIndex);
                String observationVariableName = observationVariable.getShortName();
                if (!shapefileAttributeNames.contains(observationVariableName)) {
                    netCDFAttributeDescriptors.add(atBuilder.
                        userData(VARIABLE_KEY, observationVariable).
                        userData(EXTRACTOR_KEY, NetCDFPointFeatureExtractor.generatePointFeatureExtractor(observationVariable)).
                        binding(observationVariable.getDataType().getClassType()).
                        buildDescriptor(observationVariableName));
                }
            }
            
            netCDFAttributeNames = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
            for (AttributeDescriptor attributeDescriptor : netCDFAttributeDescriptors) {
                netCDFAttributeNames.add(attributeDescriptor.getLocalName());
            }

            attributeDescriptors = new ArrayList<AttributeDescriptor>(
                    shapefileAttributeDescriptors.size() +
                    netCDFAttributeDescriptors.size());
            attributeDescriptors.addAll(shapefileAttributeDescriptors);
            attributeDescriptors.addAll(netCDFAttributeDescriptors);

        } finally {
            if (featureDataset != null) {
                featureDataset.close();
            }
        }
        return attributeDescriptors;
    }

    @Override
    protected FeatureReader<SimpleFeatureType, SimpleFeature> getFeatureReader(String typeName, Query query) throws IOException {
        if (requiresShapefileAttributes(query)) {
            if (requiresNetCDFAttributes(query)) {
                // make sure join attribute is in property list if we need to join!
                String[] properties = query.getPropertyNames();
                int joinIndex = Arrays.asList(properties).indexOf(shapefileStationAttributeName);
                if (joinIndex == -1) {
                    int tailIndex = properties.length;
                    properties = Arrays.copyOf(properties, tailIndex + 1);
                    properties[tailIndex] = shapefileStationAttributeName;
                    query.setPropertyNames(properties);
                }
            }
            return super.getFeatureReader(typeName, query);
        } else {
            try {
                List<String> propertyNames = Arrays.asList(query.getPropertyNames());
                SimpleFeatureType subTypeSchema = DataUtilities.createSubType(getSchema(), propertyNames.toArray(new String[0]));
                boolean timeStampOnly = propertyNames.size() == 1 && observationTimeVariable.getShortName().equals(propertyNames.get(0));
                if (timeStampOnly) {
                    return new DefaultFeatureReader(new NetCDFTimeStampAttributeReader(netCDFURL, subTypeSchema), subTypeSchema);
                } else {
                    return new DefaultFeatureReader(new NetCDFAttributeReader(netCDFURL, subTypeSchema), subTypeSchema);
                }
            } catch (SchemaException ex) {
                // hack
                throw new IOException(ex);
            }
        }
    }

    @Override
    protected ShapefileAttributeReader getAttributesReader(boolean readDBF, Query query, String[] properties) throws IOException {
        if (requiresNetCDFAttributes(query)) {
            Date time = extractTimeStampFromQuery(query);
            int joinIndex = Arrays.asList(properties).indexOf(shapefileStationAttributeName);
            return new NetCDFShapefileAttributeJoiningReader(super.getAttributesReader(true, query, properties), netCDFURL, joinIndex, time);
        } else {
            return super.getAttributesReader(readDBF, query, properties);
        }
    }
    
    private boolean requiresShapefileAttributes(Query query) {
        return QueryUtil.requiresAttributes(query, shapefileAttributeNames);
    }
    
    private boolean requiresNetCDFAttributes(Query query) {
        return QueryUtil.requiresAttributes(query, netCDFAttributeNames);
    }
    
    private Date extractTimeStampFromQuery(Query query) {
        return QueryUtil.extractValueFromQueryFilter(query, observationTimeVariable.getShortName(), Date.class);
    }
    
    @Override
    protected String createFeatureTypeName() {
        String path = netCDFURL.getPath();
        File file = new File(path);
        String name = file.getName();
        int suffixIndex = name.lastIndexOf("nc");
        if (suffixIndex > -1) {
            name = name.substring(0, suffixIndex -1);
        }
        name = name.replace(',', '_'); // Are there other characters?
        return name;
    }

    @Override
    public ReferencedEnvelope getBounds(Query query) throws IOException {
        return super.getBounds(query);
    }

    @Override
    public void dispose() {
        super.dispose();
    }

}

