package org.mskcc.cmo.metadb.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.logging.log4j.util.Strings;
import org.mskcc.cmo.common.enums.CmoSampleClass;
import org.mskcc.cmo.common.enums.SampleOrigin;
import org.mskcc.cmo.common.enums.SampleType;
import org.mskcc.cmo.common.enums.SpecimenType;
import org.mskcc.cmo.metadb.service.ValidRequestChecker;
import org.mskcc.cmo.metadb.service.util.RequestStatusLogger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ValidRequestCheckerImpl implements ValidRequestChecker {

    @Autowired
    private RequestStatusLogger requestStatusLogger;

    @Value("${igo.cmo_request_filter:false}")
    private Boolean igoCmoRequestFilter;

    private final ObjectMapper mapper = new ObjectMapper();
    private static final Log LOG = LogFactory.getLog(ValidRequestCheckerImpl.class);

    /**
     * Checks if the request is a cmoRequest and has a requestId, and returns a
     * filtered request JSON.
     * - a filtered JSON implies that some samples have been removed which were
     *   considered invalid
     *
     * <p>Extracts the sample list from the request JSON.
     * - If total number of samples available matches the number of valid samples then the
     *   whole request is considered valid.
     * - If the number of valid samples is zero, the request is invalid and will be logged.
     * - If the number of valid samples is less than the total number of samples that came
     *   with the request then the request is still considered valid but the request is
     *   logged by the request status logger to keep note of the invalid samples.
     * @throws IOException
     */
    @Override
    public String getFilteredValidRequestJson(String requestJson) throws IOException {
        // if json is blank then nothing to do, return null
        if (StringUtils.isAllBlank(requestJson)) {
            return null;
        }
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> requestJsonMap = mapper.readValue(requestJson, Map.class);
        boolean isCmoRequest = isCmo(requestJsonMap);
        boolean hasRequestId = hasRequestId(requestJsonMap);

        // if requestId is blank then nothing to do, return null
        if (!hasRequestId) {
            LOG.info("CMO request failed sanity checking - missing requestId...");
            requestStatusLogger.logRequestStatus(requestJson,
                    RequestStatusLogger.StatusType.CMO_REQUEST_MISSING_REQ_FIELDS);
            return null;
        }

        // if cmo filter is enabled then skip request if it is non-cmo
        if (igoCmoRequestFilter && !isCmoRequest) {
            LOG.info("CMO request filter enabled - skipping non-CMO request: "
                    + getRequestId(requestJsonMap));
            return null;
        }

        // extract sample list from request json
        Object[] sampleList = mapper.convertValue(requestJsonMap.get("samples"),
                Object[].class);
        List<Object> validSampleList = new ArrayList<Object>();
        int numOfSamples = sampleList.length; // orig num of samples in request

        // validate each sample json and add to validSampleList if it passes check
        for (Object sample: sampleList) {
            Map<String, String> sampleMap = mapper.convertValue(sample, Map.class);
            if (isCmoRequest && isValidCmoSample(sampleMap, hasRequestId)) {
                validSampleList.add(sample);
            } else if (!isCmoRequest && isValidNonCmoSample(sampleMap)) {
                validSampleList.add(sample);
            }
        }

        // update 'samples' field in json with valid samples list
        // if valid samples total does not match the original samples total then
        // log request with request status logger but allow request to be published
        if (validSampleList.size() > 0) {
            if (validSampleList.size() < numOfSamples) {
                requestJsonMap.replace("samples", validSampleList.toArray(new Object[0]));
                LOG.info("CMO request passed sanity checking - missing samples...");
                requestStatusLogger.logRequestStatus(requestJson,
                        RequestStatusLogger.StatusType.CMO_REQUEST_MISSING_REQ_FIELDS);
            }
            return mapper.writeValueAsString(requestJsonMap);
        }
        LOG.error("Request failed sanity checking - logging request status...");
        requestStatusLogger.logRequestStatus(requestJson,
                RequestStatusLogger.StatusType.CMO_REQUEST_FAILED_SANITY_CHECK);
        return null;
    }

    /**
     * Evaluates request metadata and returns a boolean based on whether the request data
     * passes all sanity checks.
     * @throws IOException
     */
    @Override
    public Boolean isValidRequestMetadataJson(String requestJson)
            throws IOException {
        if (StringUtils.isAllBlank(requestJson)) {
            return Boolean.FALSE;
        }
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> requestJsonMap = mapper.readValue(requestJson, Map.class);
        boolean isCmoRequest = isCmo(requestJsonMap);
        boolean hasRequestId = hasRequestId(requestJsonMap);

        // if requestId is blank then nothing to do, return null
        if (!hasRequestId) {
            LOG.info("CMO request update failed sanity checking - missing requestId...");
            return Boolean.FALSE;
        }

        // if cmo filter is enabled then skip request if it is non-cmo
        if (igoCmoRequestFilter && !isCmoRequest) {
            LOG.info("CMO request filter enabled - skipping non-CMO request: "
                    + getRequestId(requestJsonMap));
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    /**
     * Evaluates sample metadata and returns a boolean based on whether the sample data
     * passes all sanity checks.
     * - Checks if the sample has all the required fields for CMO label generation
     * - If any of the following are missing then returns false:
     *   -  request id
     *   - investigator sample id
     *   - cmo patient id
     *   - specimen type
     *   - sample type
     *   - normalized patient id
     * @throws JsonProcessingException or JsonMappingException
     */
    @Override
    public Boolean isValidCmoSample(Map<String, String> sampleMap,
            boolean hasRequestId) throws JsonMappingException, JsonProcessingException {
        if (sampleMap == null || sampleMap.isEmpty()) {
            return Boolean.FALSE;
        }
        if (!hasRequestId || !hasInvestigatorSampleId(sampleMap)
                || !hasBaitSet(sampleMap)
                || !hasCmoPatientId(sampleMap)
                || !(hasValidSpecimenType(sampleMap) && hasSampleType(sampleMap))
                || !hasNormalizedPatientId(sampleMap)) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    /**
     * Evaluates sample metadata for samples from NON-CMO requests.
     * - Checks if sample map has all required fields.
     * - If bait set or normalized patient id are  missing then returns false.
     * @throws JsonProcessingException or JsonMappingException
     */
    @Override
    public Boolean isValidNonCmoSample(Map<String, String> sampleMap)
            throws JsonMappingException, JsonProcessingException {
        if (sampleMap == null
                || sampleMap.isEmpty()
                || !hasBaitSet(sampleMap)
                || !hasNormalizedPatientId(sampleMap)) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    @Override
    public Boolean isCmo(String json) throws JsonProcessingException {
        if (json == null || Strings.isBlank(json)) {
            return null;
        }
        Map<String, Object> jsonMap = mapper.readValue(json, Map.class);
        return isCmo(jsonMap);
    }

    private Boolean isCmo(Map<String, Object> jsonMap) throws JsonProcessingException {
        String isCMO = getIsCmo(jsonMap);
        if ((isCMO == null)
                || Strings.isBlank(isCMO)) {
            return Boolean.FALSE;
        }
        return Boolean.valueOf(isCMO);
    }

    private String getIsCmo(Map<String, Object> jsonMap) throws JsonProcessingException {
        if (jsonMap.containsKey("isCmoRequest")) {
            return jsonMap.get("isCmoRequest").toString();
        }
        if (jsonMap.containsKey("additionalProperties")) {
            Map<String, String> additionalProperties = mapper.convertValue(jsonMap.get("additionalProperties"), Map.class);
            if (additionalProperties.containsKey("isCmoSample")) {
                return additionalProperties.get("isCmoSample");
            }
        }
        return null;
    }

    @Override
    public String getRequestId(String json) throws JsonProcessingException {
        if (json == null || Strings.isBlank(json)) {
            return null;
        }
        Map<String, Object> jsonMap = mapper.readValue(json, Map.class);
        return getRequestId(jsonMap);
    }

    private String getRequestId(Map<String, Object> jsonMap) throws JsonProcessingException {
        if (jsonMap.containsKey("requestId")) {
            return jsonMap.get("requestId").toString();
        }
        if (jsonMap.containsKey("igoRequestId")) {
            return jsonMap.get("igoRequestId").toString();
        }
        if (jsonMap.containsKey("additionalProperties")) {
            Map<String, String> additionalProperties = mapper.convertValue(jsonMap.get("additionalProperties"), Map.class);
            if (additionalProperties.containsKey("requestId")) {
                return additionalProperties.get("requestId");
            }
            if (additionalProperties.containsKey("igoRequestId")) {
                return additionalProperties.get("igoRequestId");
            }
        }
        return null;
    }

    private Boolean hasRequestId(Map<String, Object> jsonMap) throws JsonProcessingException {
        String requestId = getRequestId(jsonMap);
        return (requestId != null && !Strings.isBlank(requestId));
    }

    @Override
    public Boolean hasRequestId(String json) throws JsonProcessingException {
        String requestId = getRequestId(json);
        return (requestId != null && !Strings.isBlank(requestId));
    }

    private Boolean hasBaitSet(Map<String, String> sampleMap) {
        return !Strings.isBlank(sampleMap.get("baitSet"));
    }

    private Boolean hasInvestigatorSampleId(Map<String, String> sampleMap) {
        return !Strings.isBlank(sampleMap.get("investigatorSampleId"));
    }

    private Boolean hasCmoPatientId(Map<String, String> sampleMap) {
        return !Strings.isBlank(sampleMap.get("cmoPatientId"));
    }

    /**
     * Determines whether sample has a valid specimen type or has a valid alternative
     * to fall back on.
     *
     * <p>Option 1: specimen type is cellline, pdx, xenograft, xenograftderivedcelline, organoid
     * Option 2: if none from option 1 then fall back on sample origin
     *
     * @param sampleMap
     * @return
     */
    private Boolean hasValidSpecimenType(Map<String, String> sampleMap) {
        //this can also be sampleClass
        Object specimenTypeObject = ObjectUtils.firstNonNull(sampleMap.get("specimenType"),
               sampleMap.get("sampleClass"));
        String specimenType = String.valueOf(specimenTypeObject);
        if (specimenTypeObject == null
                || Strings.isBlank(specimenType)
                || !EnumUtils.isValidEnumIgnoreCase(SpecimenType.class, specimenType)) {
            return hasCmoSampleClass(sampleMap);
        }
        // check if specimen type is cellline, pdx, xenograft, xenograftderivedcellline, or organoid
        if (SpecimenType.CELLLINE.getValue().equalsIgnoreCase(specimenType)
                || SpecimenType.PDX.getValue().equalsIgnoreCase(specimenType)
                || SpecimenType.XENOGRAFT.getValue().equalsIgnoreCase(specimenType)
                || SpecimenType.XENOGRAFTDERIVEDCELLLINE.getValue().equalsIgnoreCase(specimenType)
                || SpecimenType.ORGANOID.getValue().equalsIgnoreCase(specimenType)) {
            return hasCmoSampleClass(sampleMap);
        }
        // if specimen type is none of the above then check if exosome or cfdna
        // and use sample origin if true
        if (SpecimenType.EXOSOME.getValue().equalsIgnoreCase(specimenType)
                || SpecimenType.CFDNA.getValue().equalsIgnoreCase(specimenType)) {
            return hasSampleOrigin(sampleMap);
        }
        return Boolean.TRUE;
    }

    private Boolean hasCmoSampleClass(Map<String, String> sampleMap) {
        Object cmoSampleClassObject = ObjectUtils.firstNonNull(sampleMap.get("cmoSampleClass"),
                sampleMap.get("sampleType"));
        String cmoSampleClass = String.valueOf(cmoSampleClassObject);
        if (cmoSampleClassObject == null
                || Strings.isBlank(cmoSampleClass)
                || !EnumUtils.isValidEnumIgnoreCase(CmoSampleClass.class,
                        cmoSampleClass)) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    private Boolean hasSampleOrigin(Map<String, String> sampleMap) {
        if (Strings.isBlank(sampleMap.get("sampleOrigin"))
                || !EnumUtils.isValidEnumIgnoreCase(SampleOrigin.class, sampleMap.get("sampleOrigin"))) {
            return Boolean.FALSE;
        }
        return Boolean.TRUE;
    }

    /**
     * Determines whether sample type is valid.
     * - if sample type is null or empty then check na to extract
     * - if sampletype is pooled library then check recipe/baitset
     * - return true if sample type is a valid enum
     * @throws JsonProcessingException or JsonMappingException
     */
    private Boolean hasSampleType(Map<String, String> sampleMap)
            throws JsonMappingException, JsonProcessingException {
        String sampleType = null;
        if (sampleMap.containsKey("cmoSampleIdFields")) {
            Map<String, String> cmoSampleIdFields = mapper.convertValue(
                    sampleMap.get("cmoSampleIdFields"), Map.class);
            // relax the check on naToExtract and instead see if field is simply present
            // if naToExtract field is present but empty then the label generator assumes DNA
            sampleType = cmoSampleIdFields.get("sampleType");
        }

        return ((Strings.isBlank(sampleType) && hasNAtoExtract(sampleMap))
                || (SampleType.POOLED_LIBRARY.getValue().equalsIgnoreCase(sampleType)
                && hasBaitSet(sampleMap))
                || EnumUtils.isValidEnumIgnoreCase(SampleType.class, sampleType));
    }

    private Boolean hasNAtoExtract(Map<String, String> sampleMap)
            throws JsonMappingException, JsonProcessingException {
        if (sampleMap.containsKey("cmoSampleIdFields")) {
            Map<String, String> cmoSampleIdFields = mapper.convertValue(
                    sampleMap.get("cmoSampleIdFields"), Map.class);
            // relax the check on naToExtract and instead see if field is simply present
            // if naToExtract field is present but empty then the label generator assumes DNA
            return cmoSampleIdFields.containsKey("naToExtract");
        }
        return Boolean.FALSE;
    }

    private Boolean hasNormalizedPatientId(Map<String, String> sampleMap)
            throws JsonMappingException, JsonProcessingException {
        if (sampleMap.containsKey("cmoSampleIdFields")) {
            Map<String, String> cmoSampleIdfields = mapper.convertValue(
                    sampleMap.get("cmoSampleIdFields"), Map.class);
            return cmoSampleIdfields.containsKey("normalizedPatientId");
        }
        return Boolean.FALSE;
    }
}
