package org.sagebionetworks.evaluation.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.sagebionetworks.evaluation.model.SubmissionStatusEnum.REJECTED;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.sagebionetworks.evaluation.dbo.SubmissionDBO;
import org.sagebionetworks.evaluation.model.Evaluation;
import org.sagebionetworks.evaluation.model.EvaluationRound;
import org.sagebionetworks.evaluation.model.Submission;
import org.sagebionetworks.evaluation.model.SubmissionBundle;
import org.sagebionetworks.evaluation.model.SubmissionContributor;
import org.sagebionetworks.evaluation.model.SubmissionStatus;
import org.sagebionetworks.evaluation.model.SubmissionStatusEnum;
import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.ids.IdType;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.AccessControlListDAO;
import org.sagebionetworks.repo.model.AuthorizationConstants.BOOTSTRAP_PRINCIPAL;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.EntityBundle;
import org.sagebionetworks.repo.model.EntityType;
import org.sagebionetworks.repo.model.GroupMembersDAO;
import org.sagebionetworks.repo.model.IdAndEtag;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.Node;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.ObjectType;
import org.sagebionetworks.repo.model.Team;
import org.sagebionetworks.repo.model.TeamDAO;
import org.sagebionetworks.repo.model.UserGroup;
import org.sagebionetworks.repo.model.UserGroupDAO;
import org.sagebionetworks.repo.model.annotation.v2.Annotations;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsV2TestUtils;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsV2Utils;
import org.sagebionetworks.repo.model.annotation.v2.AnnotationsValueType;
import org.sagebionetworks.repo.model.dbo.dao.TestUtils;
import org.sagebionetworks.repo.model.dbo.file.FileHandleDao;
import org.sagebionetworks.repo.model.docker.DockerRepository;
import org.sagebionetworks.repo.model.file.S3FileHandle;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.jdo.NodeTestUtils;
import org.sagebionetworks.repo.model.table.AnnotationType;
import org.sagebionetworks.repo.model.table.ObjectAnnotationDTO;
import org.sagebionetworks.repo.model.table.ObjectDataDTO;
import org.sagebionetworks.repo.model.table.SubType;
import org.sagebionetworks.repo.model.util.ModelConstants;
import org.sagebionetworks.repo.web.NotFoundException;
import org.sagebionetworks.schema.adapter.JSONObjectAdapter;
import org.sagebionetworks.schema.adapter.JSONObjectAdapterException;
import org.sagebionetworks.schema.adapter.org.json.JSONObjectAdapterImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import com.google.common.collect.ImmutableList;

@ExtendWith(SpringExtension.class)
@ContextConfiguration(locations = { "classpath:jdomodels-test-context.xml" })
public class SubmissionDAOImplTest {
 
    @Autowired
    private SubmissionDAO submissionDAO;
    
    @Autowired
    private SubmissionStatusDAO submissionStatusDAO;
    
    @Autowired
    private EvaluationDAO evaluationDAO;
	
    @Autowired
	private NodeDAO nodeDAO;
	
    @Autowired
	private FileHandleDao fileHandleDAO;
 
	@Autowired
	UserGroupDAO userGroupDAO;
	
	@Autowired
	GroupMembersDAO groupMembersDAO;
	
	@Autowired
	TeamDAO teamDAO;
	
	@Autowired
	AccessControlListDAO aclDAO;

	@Autowired
	private IdGenerator idGenerator;
	
    private static final String SUBMISSION_ID = "206";
    private static final String SUBMISSION_2_ID = "307";
    private static final String SUBMISSION_3_ID = "408";
    private static final String SUBMISSION_4_ID = "509";
    private static final String USERID_DOES_NOT_EXIST = "2";
    private static final String EVALID_DOES_NOT_EXIST = "456";
    private static final String SUBMISSION_NAME = "test submission";
    private static final Long VERSION_NUMBER = 1L;
    private static final long CREATION_TIME_STAMP = System.currentTimeMillis();
	private static final String DOCKER_DIGEST = "sha256:abcdef...";

    private String nodeId;
    private String dockerNodeId;
	private String userId;
	private String userId2;
	
    private String evalId;
    private String evalId2;
    private AccessControlList acl;
    private String fileHandleId;
    private Submission submission;
    private Submission submission2;
    private Submission submission3;
    private Team submissionTeam;
    private Team submissionTeam2;
    
    // create a team and add the given ID as a member
	private Team createTeam(String ownerId) throws NotFoundException {
		Team team = new Team();
		UserGroup ug = new UserGroup();
		ug.setIsIndividual(false);
		Long id = userGroupDAO.create(ug);
		
		team.setId(id.toString());
		team.setCreatedOn(new Date());
		team.setCreatedBy(ownerId);
		team.setModifiedOn(new Date());
		team.setModifiedBy(ownerId);
		Team created = teamDAO.create(team);
		try {
			groupMembersDAO.addMembers(id.toString(), Arrays.asList(new String[]{ownerId}));
		} catch (NotFoundException e) {
			throw new RuntimeException(e);
		}
			
		return created;
	}
	
	private void deleteTeam(Team team) throws NotFoundException {
		teamDAO.delete(team.getId());
		userGroupDAO.delete(team.getId());
	}
	
	private static Random random = new Random();
	
	private Submission newSubmission(String submissionId, String userId, String nodeId, Date createdDate) {
		Submission submission = new Submission();
        submission.setCreatedOn(createdDate);
        submission.setId(submissionId);
        submission.setName(SUBMISSION_NAME+"_"+random.nextInt());
        submission.setEvaluationId(evalId);
        submission.setEntityId(nodeId);
        submission.setVersionNumber(VERSION_NUMBER);
        submission.setUserId(userId);
        submission.setSubmitterAlias("Team Awesome_"+random.nextInt());
        submission.setEntityBundleJSON("some bundle"+random.nextInt());
        submission.setDockerDigest(DOCKER_DIGEST);
        return submission;
	}

    @BeforeEach
    public void setUp() throws DatastoreException, InvalidModelException, NotFoundException {
    	submissionDAO.truncateAll();
    	
    	userId = BOOTSTRAP_PRINCIPAL.THE_ADMIN_USER.getPrincipalId().toString();
    	userId2 = BOOTSTRAP_PRINCIPAL.ANONYMOUS_USER.getPrincipalId().toString();
    	
    	// create a file handle
		S3FileHandle meta = TestUtils.createS3FileHandle(userId, idGenerator.generateNewId(IdType.FILE_IDS).toString());
		fileHandleId = fileHandleDAO.createFile(meta).getId();
		
    	// create a node
  		Node toCreate = NodeTestUtils.createNew(SUBMISSION_NAME, Long.parseLong(userId));
    	toCreate.setVersionComment("This is the first version of the first node ever!");
    	toCreate.setVersionLabel("0.0.1");
    	toCreate.setFileHandleId(fileHandleId);
  		toCreate.setNodeType(EntityType.file);
    	nodeId = nodeDAO.createNew(toCreate);
    	
    	// create a Docker node
  		toCreate = NodeTestUtils.createNew(SUBMISSION_NAME+"_dockerrepo", Long.parseLong(userId));
  		toCreate.setNodeType(EntityType.dockerrepo);
    	dockerNodeId = nodeDAO.createNew(toCreate);
    	
    	// create an Evaluation
        Evaluation evaluation = new Evaluation();
        evaluation.setId("1234");
        evaluation.setEtag("etag");
        evaluation.setName("name");
        evaluation.setOwnerId(userId);
        evaluation.setCreatedOn(new Date());
        evaluation.setContentSource(nodeId);
        evalId = evaluationDAO.create(evaluation, Long.parseLong(userId));
        acl = Util.createACL(evalId, Long.parseLong(userId), ModelConstants.EVALUATION_ADMIN_ACCESS_PERMISSIONS, new Date());
        acl.setId(aclDAO.create(acl, ObjectType.EVALUATION));
        
        // Create a second evaluation
        Evaluation evaluation2 = new Evaluation();
        evaluation2.setId("5678");
        evaluation2.setEtag("etag");
        evaluation2.setName("name2");
        evaluation2.setOwnerId(userId);
        evaluation2.setCreatedOn(new Date());
        evaluation2.setContentSource(nodeId);
        evalId2 = evaluationDAO.create(evaluation2, Long.parseLong(userId));
        
        // Initialize Submissions
        // submission has no team and no contributors
        submission = newSubmission(SUBMISSION_ID, userId, nodeId, new Date(CREATION_TIME_STAMP));
        
        // submission2 is a Team submission with two contributors, userId and userId2
        submission2 = newSubmission(SUBMISSION_2_ID, userId2, nodeId, new Date(CREATION_TIME_STAMP));
        submissionTeam = createTeam(userId2);
        submission2.setTeamId(submissionTeam.getId());
        SubmissionContributor submissionContributor = new SubmissionContributor();
        submissionContributor.setPrincipalId(userId2);
        Set<SubmissionContributor> scs = new HashSet<SubmissionContributor>();
        submission2.setContributors(scs);
        scs.add(submissionContributor);
		groupMembersDAO.addMembers(submissionTeam.getId(), Arrays.asList(new String[]{userId}));
		submissionContributor = new SubmissionContributor();
        submissionContributor.setPrincipalId(userId);
        scs.add(submissionContributor);
        
        // submission3 is a Team submission with one contributor, userId2
        submission3 = newSubmission(SUBMISSION_3_ID, userId2, nodeId, new Date(CREATION_TIME_STAMP));
        submission3.setTeamId(submissionTeam.getId());
        submissionContributor = new SubmissionContributor();
        submissionContributor.setPrincipalId(userId2);
        scs = new HashSet<SubmissionContributor>();
        submission3.setContributors(scs);
        scs.add(submissionContributor);
        
        
        
    }
    
    @AfterEach
    public void tearDown() throws DatastoreException, NotFoundException  {
    	for (String id : new String[]{SUBMISSION_ID, SUBMISSION_2_ID, SUBMISSION_3_ID, SUBMISSION_4_ID}) {
    		try {
    			submissionDAO.delete(id);
    		} catch (NotFoundException e)  {};
    	}
			
    	if (submissionTeam!=null) {
    		deleteTeam(submissionTeam);
    		submissionTeam=null;
    	}
		
    	if (submissionTeam2!=null) {
    		deleteTeam(submissionTeam2);
    		submissionTeam2=null;
    	}
		
		try {
			aclDAO.delete(acl.getId(), ObjectType.EVALUATION);
		} catch (NotFoundException e) {};
		
		try {
			evaluationDAO.delete(evalId);
		} catch (NotFoundException e) {};
		
		try {
			evaluationDAO.delete(evalId2);
		} catch (NotFoundException e) {};
		
    	try {
    		nodeDAO.delete(nodeId);
    	} catch (NotFoundException e) {};
    	
		if (fileHandleId!=null) fileHandleDAO.delete(fileHandleId);
		
    	try {
    		nodeDAO.delete(dockerNodeId);
    	} catch (NotFoundException e) {};
    	
    	submissionDAO.truncateAll();
    	
    }
    
    @Test
    public void testCRD() throws Exception{
        long initialCount = submissionDAO.getCount();
 
        // create Submission
        String returnedId = submissionDAO.create(submission);
        assertEquals(SUBMISSION_ID, returnedId); 
        
        // we create a second submission with contributors to test
        // that retrieval of the first submission doesn't accidentally
        // pick up the contributors to some other submission
        String returnedId2 = submissionDAO.create(submission2);
        assertEquals(SUBMISSION_2_ID, returnedId2);   
        
        // fetch it
        Submission clone = submissionDAO.get(SUBMISSION_ID);
        assertNotNull(clone);
        assertEquals(initialCount + 2, submissionDAO.getCount());
        assertEquals(submission, clone);
        
        // delete it
        submissionDAO.delete(SUBMISSION_ID);
        
		assertThrows(NotFoundException.class, () -> {
			submissionDAO.get(SUBMISSION_ID);
		});

		assertEquals(initialCount+1, submissionDAO.getCount());
    }
    
    @Test
    public void testGetBundle() throws Exception {
    	submissionDAO.create(submission);
     	createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.RECEIVED);
     	
     	SubmissionBundle bundle = submissionDAO.getBundle(SUBMISSION_ID);
    	
     	assertEquals(submissionDAO.get(SUBMISSION_ID), bundle.getSubmission());
     	SubmissionStatus status = bundle.getSubmissionStatus();
     	assertEquals(SUBMISSION_ID, status.getId());
     	assertEquals(SubmissionStatusEnum.RECEIVED, status.getStatus());
     	assertEquals(submission.getEntityId(), status.getEntityId());
     	assertEquals(submission.getVersionNumber(), status.getVersionNumber());
    }
    
    @Test
    public void testGetBundleWithNoContributors() throws Exception {
    	submissionDAO.create(submission2);
     	createSubmissionStatus(SUBMISSION_2_ID, SubmissionStatusEnum.RECEIVED);
     	
     	boolean includeContributors = true;
     	
     	SubmissionBundle bundle = submissionDAO.getBundle(SUBMISSION_2_ID, includeContributors);
    	
     	assertFalse(bundle.getSubmission().getContributors().isEmpty());
     	
     	includeContributors = false;
     	
     	bundle = submissionDAO.getBundle(SUBMISSION_2_ID, includeContributors);
    	
     	assertNull(bundle.getSubmission().getContributors());
    }

    @Test
	public void testGetBundleNotFound() throws Exception {
    	assertThrows(NotFoundException.class, () -> {
    		submissionDAO.getBundle("notfound");
    	});
	}
    
    @Test
    public void testCRDWithContributors() throws Exception{
        long initialCount = submissionDAO.getCount();
 
        // create Submission
        String returnedId = submissionDAO.create(submission2);
        assertEquals(SUBMISSION_2_ID, returnedId);   
        
        // fetch it
        Submission clone = submissionDAO.get(SUBMISSION_2_ID);
        assertNotNull(clone);
        // need to nullify the contributor createdOn dates to be able to compare to 'submission2'
        Set<SubmissionContributor> nullifiedDates = new HashSet<SubmissionContributor>();
        for (SubmissionContributor sc : clone.getContributors()) {
        	assertNotNull(sc.getCreatedOn());
        	sc.setCreatedOn(null);
        	nullifiedDates.add(sc);
        }
        clone.setContributors(nullifiedDates);
        assertEquals(initialCount + 1, submissionDAO.getCount());
        assertEquals(submission2, clone);
        
        // delete it
        submissionDAO.delete(SUBMISSION_2_ID);
        
        assertThrows(NotFoundException.class, () -> {
        	submissionDAO.get(SUBMISSION_2_ID);
        });
    	
        assertEquals(initialCount, submissionDAO.getCount());
    }
    
    @Test
    public void testAddContributor() throws Exception{
        // create Submission
        String returnedId = submissionDAO.create(submission);
        assertEquals(SUBMISSION_ID, returnedId); 
        
        SubmissionContributor sc = new SubmissionContributor();
        sc.setPrincipalId(userId);
        sc.setCreatedOn(new Date());
        submissionDAO.addSubmissionContributor(SUBMISSION_ID, sc);
        
        // test that you can't add it twice
        assertThrows(IllegalArgumentException.class, () -> {
        	submissionDAO.addSubmissionContributor(SUBMISSION_ID, sc);
        });
                
        // fetch it
        Submission clone = submissionDAO.get(SUBMISSION_ID);
        assertNotNull(clone);
        assertEquals(1, clone.getContributors().size());
        assertEquals(sc, clone.getContributors().iterator().next());
    }
    
    @Test
    public void testGetAllByUser() throws DatastoreException, NotFoundException, JSONObjectAdapterException {
    	submissionDAO.create(submission);
    	
    	// userId should have submissions
    	List<Submission> subs = submissionDAO.getAllByUser(userId, 10, 0);
    	assertEquals(1, subs.size());
    	assertEquals(subs.size(), submissionDAO.getCountByUser(userId));
       	Submission retrieved = subs.get(0);
       	submission.setCreatedOn(retrieved.getCreatedOn());
    	assertEquals(retrieved, submission);
    	
    	// userId_does_not_exist should not have any submissions
    	subs = submissionDAO.getAllByUser(USERID_DOES_NOT_EXIST, 10, 0);
    	assertEquals(0, subs.size());
    }
    
    @Test
    public void testGetAllByUserWithContributors() throws DatastoreException, NotFoundException, JSONObjectAdapterException {
    	submissionDAO.create(submission2);
    	
    	// userId should have submissions
    	List<Submission> subs = submissionDAO.getAllByUser(userId2, 10, 0);
    	assertEquals(1, subs.size());
    	Submission retrieved = subs.get(0);
    	submission2.setCreatedOn(retrieved.getCreatedOn());
        // need to nullify the contributor createdOn dates to be able to compare to 'submission2'
    	nullifyContributorCreatedOn(retrieved);
    	assertEquals(retrieved, submission2);
    }
    
    private void nullifyContributorCreatedOn(Submission retrieved) {
        Set<SubmissionContributor> nullifiedDates = new HashSet<SubmissionContributor>();
        for (SubmissionContributor sc : retrieved.getContributors()) {
        	assertNotNull(sc.getCreatedOn());
        	sc.setCreatedOn(null);
        	nullifiedDates.add(sc);
        }
        // the hash set returned by .getContributors() is invalid 
        // since we've messed with the set's contents.  So we use
        // the new one instead
        retrieved.setContributors(nullifiedDates);
    }
    
    @Test
    public void testGetAllByEvaluation() throws DatastoreException, NotFoundException, JSONObjectAdapterException {
    	submissionDAO.create(submission);
    	
    	// evalId should have submissions
    	List<Submission> subs = submissionDAO.getAllByEvaluation(evalId, 10, 0);
    	assertEquals(1, subs.size());
    	assertEquals(subs.size(), submissionDAO.getCountByEvaluation(evalId));
    	Submission retrieved = subs.get(0);
    	submission.setCreatedOn(retrieved.getCreatedOn());
    	assertEquals(retrieved, submission);
    	
    	// evalId_does_not_exist should not have any submissions
    	subs = submissionDAO.getAllByEvaluation(EVALID_DOES_NOT_EXIST, 10, 0);
    	assertEquals(0, subs.size());
    	assertEquals(0, submissionDAO.getCountByEvaluation(EVALID_DOES_NOT_EXIST));
    }
    
    @Test
    public void testGetAllByEvaluationWithContributors() throws DatastoreException, NotFoundException, JSONObjectAdapterException {
    	submissionDAO.create(submission2);
    	
    	// evalId should have submissions
    	List<Submission> subs = submissionDAO.getAllByEvaluation(evalId, 10, 0);
    	assertEquals(1, subs.size());
    	assertEquals(subs.size(), submissionDAO.getCountByEvaluation(evalId));
    	Submission retrieved = subs.get(0);
    	submission2.setCreatedOn(retrieved.getCreatedOn());
        // need to nullify the contributor createdOn dates to be able to compare to 'submission2'
     	nullifyContributorCreatedOn(retrieved);
    	assertEquals(retrieved, submission2);
    }
    
    @Test
    public void testGetAllBundlesByEvaluation() throws DatastoreException, NotFoundException, JSONObjectAdapterException {
    	submissionDAO.create(submission);
     	createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.RECEIVED);
     	
    	// evalId should have submissions
    	List<SubmissionBundle> bundles = submissionDAO.getAllBundlesByEvaluation(evalId, 10, 0);
    	assertEquals(1, bundles.size());
    	SubmissionBundle bundle = bundles.get(0);
    	submission.setCreatedOn(bundle.getSubmission().getCreatedOn());
    	assertEquals(bundle.getSubmission(), submission);
    	
     	SubmissionStatus status = bundle.getSubmissionStatus();
     	assertEquals(SUBMISSION_ID, status.getId());
     	assertEquals(SubmissionStatusEnum.RECEIVED, status.getStatus());
     	assertEquals(submission.getEntityId(), status.getEntityId());
     	assertEquals(submission.getVersionNumber(), status.getVersionNumber());
    	
    	// evalId_does_not_exist should not have any submissions
    	bundles = submissionDAO.getAllBundlesByEvaluation(EVALID_DOES_NOT_EXIST, 10, 0);
    	assertEquals(0, bundles.size());
    }
    
    private void createSubmissionStatus(String id, SubmissionStatusEnum status) {
    	createSubmissionStatus(id, status, null);
    }
    
    private void createSubmissionStatus(String id, SubmissionStatusEnum status, Annotations annotations) {
    	// create a SubmissionStatus object
    	SubmissionStatus subStatus = new SubmissionStatus();
    	subStatus.setId(id);
    	subStatus.setStatus(status);
    	subStatus.setModifiedOn(new Date());
    	subStatus.setSubmissionAnnotations(annotations);
    	subStatus.setVersionNumber(0L);
    	submissionStatusDAO.create(subStatus);
    }
    
    @Test
    public void testGetAllByEvaluationAndStatus() throws DatastoreException, NotFoundException, JSONObjectAdapterException {
    	submissionDAO.create(submission);
    	createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.RECEIVED);
    	
    	// hit evalId and hit status => should find 1 submission
    	List<Submission> subs = submissionDAO.getAllByEvaluationAndStatus(evalId, SubmissionStatusEnum.RECEIVED, 10, 0);
    	assertEquals(1, subs.size());
    	assertEquals(subs.size(), submissionDAO.getCountByEvaluationAndStatus(evalId, SubmissionStatusEnum.RECEIVED));
       	Submission retrieved = subs.get(0);
       	submission.setCreatedOn(retrieved.getCreatedOn());
    	assertEquals(retrieved, submission);
    	
    	// miss evalId and hit status => should find 0 submissions
    	subs = submissionDAO.getAllByEvaluationAndStatus(EVALID_DOES_NOT_EXIST, SubmissionStatusEnum.RECEIVED, 10, 0);
    	assertEquals(0, subs.size());
    	assertEquals(subs.size(), submissionDAO.getCountByEvaluationAndStatus(EVALID_DOES_NOT_EXIST, SubmissionStatusEnum.RECEIVED));
    	
    	// hit evalId and miss status => should find 0 submissions
    	subs = submissionDAO.getAllByEvaluationAndStatus(evalId, SubmissionStatusEnum.SCORED, 10, 0);
    	assertEquals(0, subs.size());
    	assertEquals(subs.size(), submissionDAO.getCountByEvaluationAndStatus(evalId, SubmissionStatusEnum.SCORED));
    }
    
    @Test
    public void testGetAllBundlesByEvaluationAndStatus() throws DatastoreException, NotFoundException, JSONObjectAdapterException {
    	submissionDAO.create(submission);
     	createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.RECEIVED);
     	
    	List<SubmissionBundle> bundles = submissionDAO.getAllBundlesByEvaluationAndStatus(evalId, SubmissionStatusEnum.RECEIVED, 10, 0);
    	assertEquals(Collections.singletonList(submissionDAO.getBundle(SUBMISSION_ID)), bundles);
    	
    	bundles = submissionDAO.getAllBundlesByEvaluationAndStatus(evalId, SubmissionStatusEnum.CLOSED, 10, 0);
    	assertTrue(bundles.isEmpty());
    }
    
   @Test
   public void testGetAllByEvaluationAndStatusWithContributors() throws DatastoreException, NotFoundException, JSONObjectAdapterException {
   	submissionDAO.create(submission2);
   	
   	// create a SubmissionStatus object
   	createSubmissionStatus(SUBMISSION_2_ID, SubmissionStatusEnum.RECEIVED);
       	
   	// hit evalId and hit status => should find 1 submission
   	List<Submission> subs = submissionDAO.getAllByEvaluationAndStatus(evalId, SubmissionStatusEnum.RECEIVED, 10, 0);
   	assertEquals(1, subs.size());
   	assertEquals(subs.size(), submissionDAO.getCountByEvaluationAndStatus(evalId, SubmissionStatusEnum.RECEIVED));
    Submission retrieved = subs.get(0);
    submission2.setCreatedOn(retrieved.getCreatedOn());
    // need to nullify the contributor createdOn dates to be able to compare to 'submission2'
 	nullifyContributorCreatedOn(retrieved);
   	assertEquals(retrieved, submission2);
   	
   }
   
    @Test
    public void testGetAllByEvaluationAndUser() throws DatastoreException, NotFoundException, JSONObjectAdapterException {
    	submissionDAO.create(submission);
    	
    	// hit evalId and hit user => should find 1 submission
    	List<Submission> subs = submissionDAO.getAllByEvaluationAndUser(evalId, userId, 10, 0);
    	assertEquals(1, subs.size());
    	assertEquals(subs.size(), submissionDAO.getCountByEvaluationAndUser(evalId, userId));
      	Submission retrieved = subs.get(0);
      	submission.setCreatedOn(retrieved.getCreatedOn());
    	assertEquals(retrieved, submission);
    	
    	// miss evalId and hit user => should find 0 submissions
    	subs = submissionDAO.getAllByEvaluationAndUser(EVALID_DOES_NOT_EXIST, userId, 10, 0);
    	assertEquals(0, subs.size());
    	assertEquals(subs.size(), submissionDAO.getCountByEvaluationAndUser(EVALID_DOES_NOT_EXIST, userId));
    	
    	// hit evalId and miss user => should find 0 submissions
    	subs = submissionDAO.getAllByEvaluationAndUser(evalId, USERID_DOES_NOT_EXIST, 10, 0);
    	assertEquals(0, subs.size());
    	assertEquals(subs.size(), submissionDAO.getCountByEvaluationAndUser(evalId, USERID_DOES_NOT_EXIST));
    }
    
    @Test
    public void testGetAllByEvaluationAndUserWithContributors() throws DatastoreException, NotFoundException, JSONObjectAdapterException {
    	submissionDAO.create(submission2);
    	
    	// hit evalId and hit user => should find 1 submission
    	List<Submission> subs = submissionDAO.getAllByEvaluationAndUser(evalId, userId2, 10, 0);
    	assertEquals(1, subs.size());
    	assertEquals(subs.size(), submissionDAO.getCountByEvaluationAndUser(evalId, userId2));
      	Submission retrieved = subs.get(0);
      	submission2.setCreatedOn(retrieved.getCreatedOn());
        // need to nullify the contributor createdOn dates to be able to compare to 'submission2'
     	nullifyContributorCreatedOn(retrieved);
   	assertEquals(retrieved, submission2);
    }
    
    @Test
    public void testGetAllBundlesByEvaluationAndUser() throws DatastoreException, NotFoundException, JSONObjectAdapterException {
    	submissionDAO.create(submission);
     	createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.RECEIVED);
     	
    	List<SubmissionBundle> bundles = submissionDAO.getAllBundlesByEvaluationAndUser(evalId, userId, 10, 0);
    	assertEquals(Collections.singletonList(submissionDAO.getBundle(SUBMISSION_ID)), bundles);
    	
    	bundles = submissionDAO.getAllBundlesByEvaluationAndUser(evalId, userId2, 10, 0);
    	assertTrue(bundles.isEmpty());
    }

    @Test
    public void testDtoToDbo() {
    	Submission subDTO = new Submission();
    	Submission subDTOclone = new Submission();
    	SubmissionDBO subDBO = new SubmissionDBO();
    	SubmissionDBO subDBOclone = new SubmissionDBO();

    	subDTO.setEvaluationId("123");
    	subDTO.setCreatedOn(new Date());
    	subDTO.setEntityId("syn456");
    	subDTO.setId("789");
    	subDTO.setEvaluationRoundId("44444");
    	subDTO.setName("name");
    	subDTO.setUserId("42");
    	subDTO.setSubmitterAlias("Team Awesome");
    	subDTO.setVersionNumber(1L);
    	subDTO.setEntityBundleJSON("foo");
    	subDTO.setDockerRepositoryName("docker.synapse.org/syn789/arepo");
    	subDTO.setDockerDigest("sha256:abcdef0123456");

    	SubmissionUtils.copyDtoToDbo(subDTO, subDBO);
    	SubmissionUtils.copyDboToDto(subDBO, subDTOclone);
    	SubmissionUtils.copyDtoToDbo(subDTOclone, subDBOclone);

    	assertEquals(subDTO, subDTOclone);
    	assertEquals(subDBO, subDBOclone);
    }

    @Test
    public void testDtoToDboNullColumn() {
    	Submission subDTO = new Submission();
    	Submission subDTOclone = new Submission();
    	SubmissionDBO subDBO = new SubmissionDBO();
    	SubmissionDBO subDBOclone = new SubmissionDBO();

    	subDTO.setEvaluationId("123");
    	subDTO.setCreatedOn(new Date());
    	subDTO.setEntityId("syn456");
    	subDTO.setId("789");
    	subDTO.setName("name");
    	subDTO.setUserId("42");
    	subDTO.setSubmitterAlias("Team Awesome");
    	subDTO.setVersionNumber(1L);
    	// null EntityBundle

    	SubmissionUtils.copyDtoToDbo(subDTO, subDBO);
    	SubmissionUtils.copyDboToDto(subDBO, subDTOclone);
    	SubmissionUtils.copyDtoToDbo(subDTOclone, subDBOclone);

    	assertEquals(subDTO, subDTOclone);
    	assertEquals(subDBO, subDBOclone);
    	assertNull(subDTOclone.getEntityBundleJSON());
    	assertNull(subDBOclone.getEntityBundle());
    	assertNull(subDTOclone.getDockerRepositoryName());
    	assertNull(subDTOclone.getDockerDigest());
    }
    
    @Test
    public void testMissingVersionNumber() throws DatastoreException, JSONObjectAdapterException {
        submission.setVersionNumber(null);
        
        assertThrows(IllegalArgumentException.class, () -> {
        	submissionDAO.create(submission);
        });
    }
    
    // Should be able to have null entity bundle
    @Test
    public void testPLFM1859() {
    	submission.setEntityBundleJSON(null);
    	String id = submissionDAO.create(submission);
    	assertNotNull(id);
    }
    
    @Test
    public void testQueryTeamSubmissions() throws Exception {
    	submissionDAO.create(submission);
     	createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.SCORED);
     	submissionDAO.create(submission2);
      	createSubmissionStatus(SUBMISSION_2_ID, SubmissionStatusEnum.SCORED);
      	     	
     	Date startDateIncl = new Date(CREATION_TIME_STAMP-1000L);
     	Date endDateExcl = new Date(CREATION_TIME_STAMP+1000L);
     	Set<SubmissionStatusEnum> statuses = new HashSet<SubmissionStatusEnum>();
     	statuses.add(SubmissionStatusEnum.SCORED);
     	
      	// happy case
     	assertEquals(1L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId), 
      			Long.parseLong(submissionTeam.getId()), startDateIncl, endDateExcl, statuses));
      	// also works if start, end or status filters are omitted
       	assertEquals(1L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId), 
      			Long.parseLong(submissionTeam.getId()), null, null, null));
    	assertEquals(1L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId), 
      			Long.parseLong(submissionTeam.getId()), startDateIncl, null, null));
    	assertEquals(1L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId), 
      			Long.parseLong(submissionTeam.getId()), null, endDateExcl, null));
    	assertEquals(1L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId), 
      			Long.parseLong(submissionTeam.getId()), startDateIncl, endDateExcl, null));
      	assertEquals(1L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId), 
      			Long.parseLong(submissionTeam.getId()), null, null, statuses));
      	
       	// ok if right at start of time range
    	assertEquals(1L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId), 
      			Long.parseLong(submissionTeam.getId()), new Date(CREATION_TIME_STAMP), endDateExcl, statuses));
      	// fails if outside time range
     	assertEquals(0L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId), 
      			Long.parseLong(submissionTeam.getId()), new Date(CREATION_TIME_STAMP+10L), endDateExcl, statuses));
     	assertEquals(0L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId), 
      			Long.parseLong(submissionTeam.getId()), startDateIncl, new Date(CREATION_TIME_STAMP), statuses));
     	// fails if we look for a different status
    	assertEquals(0L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId), 
      			Long.parseLong(submissionTeam.getId()), null, null, Collections.singleton(REJECTED)));
    	// fails if we look for a different Team
       	assertEquals(0L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId), 
      			Long.parseLong(submissionTeam.getId())-100L, null, null, null));
       	// fails if we look for a different evaluation
       	assertEquals(0L, submissionDAO.countSubmissionsByTeam(Long.parseLong(evalId)-100L, 
      			Long.parseLong(submissionTeam.getId()), null, null, null));
    	
    }
    
    @Test
    public void testCountSubmissionsByTeamMembers() throws Exception {
    	submissionDAO.create(submission);
     	createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.SCORED);
     	submissionDAO.create(submission2);
     	createSubmissionStatus(SUBMISSION_2_ID, SubmissionStatusEnum.SCORED);
    	submissionDAO.create(submission3);
     	createSubmissionStatus(SUBMISSION_3_ID, SubmissionStatusEnum.SCORED);
      	     	
     	Date startDateIncl = new Date(CREATION_TIME_STAMP-1000L);
     	Date endDateExcl = new Date(CREATION_TIME_STAMP+1000L);
     	Set<SubmissionStatusEnum> statuses = new HashSet<SubmissionStatusEnum>();
     	statuses.add(SubmissionStatusEnum.SCORED);
     	
     	// happy case
     	Map<Long,Long> expected = new HashMap<Long,Long>();
    	expected.put(Long.parseLong(userId), 1L); // userId contributed to submission2
    	expected.put(Long.parseLong(userId2), 2L); // userId2 contributed to submission2, submission3
     	assertEquals(expected, submissionDAO.countSubmissionsByTeamMembers(Long.parseLong(evalId), 
			Long.parseLong(submissionTeam.getId()), null, null, null));
     	assertEquals(expected, submissionDAO.countSubmissionsByTeamMembers(Long.parseLong(evalId), 
			Long.parseLong(submissionTeam.getId()), startDateIncl, endDateExcl, statuses));
     	
     	// what if one submission doesn't match our criteria?
     	SubmissionStatus sub3Status = submissionStatusDAO.get(SUBMISSION_3_ID);
     	sub3Status.setStatus(REJECTED);
     	submissionStatusDAO.update(Collections.singletonList(sub3Status));
     	assertEquals(expected, submissionDAO.countSubmissionsByTeamMembers(Long.parseLong(evalId), 
			Long.parseLong(submissionTeam.getId()), null, null, null));
       	expected.put(Long.parseLong(userId2), 1L);
    	assertEquals(expected, submissionDAO.countSubmissionsByTeamMembers(Long.parseLong(evalId), 
			Long.parseLong(submissionTeam.getId()), startDateIncl, endDateExcl, statuses));
     }
    
    @Test
    public void testCountSubmissionsByContributor() throws Exception {
    	submissionDAO.create(submission);
     	createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.SCORED);
     	submissionDAO.create(submission2);
     	createSubmissionStatus(SUBMISSION_2_ID, SubmissionStatusEnum.SCORED);
    	submissionDAO.create(submission3);
     	createSubmissionStatus(SUBMISSION_3_ID, SubmissionStatusEnum.SCORED);
      	     	
     	Date startDateIncl = new Date(CREATION_TIME_STAMP-1000L);
     	Date endDateExcl = new Date(CREATION_TIME_STAMP+1000L);
     	Set<SubmissionStatusEnum> statuses = new HashSet<SubmissionStatusEnum>();
     	statuses.add(SubmissionStatusEnum.SCORED);

    	assertEquals(1L, submissionDAO.countSubmissionsByContributor(Long.parseLong(evalId), 
			Long.parseLong(userId), startDateIncl, endDateExcl, statuses));
    	assertEquals(2L, submissionDAO.countSubmissionsByContributor(Long.parseLong(evalId), 
			Long.parseLong(userId2), startDateIncl, endDateExcl, statuses));
    }
    
    @Test
    public void testGetTeamMembersSubmittingElsewhereNoTraitors() throws Exception {
    	submissionDAO.create(submission);
     	createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.SCORED);
     	submissionDAO.create(submission2);
     	createSubmissionStatus(SUBMISSION_2_ID, SubmissionStatusEnum.SCORED);
    	submissionDAO.create(submission3);
     	createSubmissionStatus(SUBMISSION_3_ID, SubmissionStatusEnum.SCORED);
      	     	
     	Date startDateIncl = new Date(CREATION_TIME_STAMP-1000L);
     	Date endDateExcl = new Date(CREATION_TIME_STAMP+1000L);
     	Set<SubmissionStatusEnum> statuses = new HashSet<SubmissionStatusEnum>();
     	statuses.add(SubmissionStatusEnum.SCORED);

     	assertTrue(submissionDAO.getTeamMembersSubmittingElsewhere(Long.parseLong(evalId), 
			Long.parseLong(submissionTeam.getId()), startDateIncl, endDateExcl, statuses).isEmpty());
     }
    
    @Test
    public void testGetTeamMembersSubmittingElsewhereOtherIndividSub() throws Exception {
     	// have userId2 make an individual submission
        Submission individSub = newSubmission(SUBMISSION_4_ID, userId2, nodeId, new Date(CREATION_TIME_STAMP));
        SubmissionContributor submissionContributor = new SubmissionContributor();
        submissionContributor.setPrincipalId(userId2);
        individSub.setContributors(Collections.singleton(submissionContributor));
    	submissionDAO.create(individSub);
     	createSubmissionStatus(SUBMISSION_4_ID, SubmissionStatusEnum.SCORED);
       
     	assertEquals(
     			Collections.singletonList(Long.parseLong(userId2)),
     			submissionDAO.getTeamMembersSubmittingElsewhere(Long.parseLong(evalId), 
			Long.parseLong(submissionTeam.getId()), null, null, null));
     }
    
    @Test
    public void testGetTeamMembersSubmittingElsewhereOtherTeamSub() throws Exception {
     	// have userId2 make a Team submission
        Submission individSub = newSubmission(SUBMISSION_4_ID, userId2, nodeId, new Date(CREATION_TIME_STAMP));
        submissionTeam2 = createTeam(userId2);
        individSub.setTeamId(submissionTeam2.getId());
        SubmissionContributor submissionContributor = new SubmissionContributor();
        submissionContributor.setPrincipalId(userId2);
        individSub.setContributors(Collections.singleton(submissionContributor));
    	submissionDAO.create(individSub);
     	createSubmissionStatus(SUBMISSION_4_ID, SubmissionStatusEnum.SCORED);
       
     	assertEquals(
     			Collections.singletonList(Long.parseLong(userId2)),
     			submissionDAO.getTeamMembersSubmittingElsewhere(Long.parseLong(evalId), 
			Long.parseLong(submissionTeam.getId()), null, null, null));
     }
    
    @Test
    public void testHasContributedToTeamSubmission() throws Exception {
    	submissionDAO.create(submission);
     	createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.SCORED);
     	submissionDAO.create(submission2);
     	createSubmissionStatus(SUBMISSION_2_ID, SubmissionStatusEnum.SCORED);
    	submissionDAO.create(submission3);
     	createSubmissionStatus(SUBMISSION_3_ID, SubmissionStatusEnum.SCORED);
      	     	
     	Date startDateIncl = new Date(CREATION_TIME_STAMP-1000L);
     	Date endDateExcl = new Date(CREATION_TIME_STAMP+1000L);
     	Set<SubmissionStatusEnum> statuses = new HashSet<SubmissionStatusEnum>();
     	statuses.add(SubmissionStatusEnum.SCORED);

    	assertEquals(true, submissionDAO.hasContributedToTeamSubmission(Long.parseLong(evalId), 
			Long.parseLong(userId), startDateIncl, endDateExcl, statuses));
    	assertEquals(true, submissionDAO.hasContributedToTeamSubmission(Long.parseLong(evalId), 
			Long.parseLong(userId2), startDateIncl, endDateExcl, statuses));
    	
    	//user1 no longer has any team submissions
    	submissionDAO.delete(SUBMISSION_2_ID);
       	assertFalse(submissionDAO.hasContributedToTeamSubmission(Long.parseLong(evalId), 
    			Long.parseLong(userId), startDateIncl, endDateExcl, statuses));
       	
    	//user2 still has contributed to submission3
    	assertTrue(submissionDAO.hasContributedToTeamSubmission(Long.parseLong(evalId), 
    			Long.parseLong(userId2), startDateIncl, endDateExcl, statuses));
    }

    @Test
    public void testGetCreatedByWithNullSubId(){
    	assertThrows(IllegalArgumentException.class, () -> {
    		submissionDAO.getCreatedBy(null);
    	});
    }

    @Test
    public void testGetCreatedByWithNotExistingSubmission(){
    	assertThrows(NotFoundException.class, () -> {
    		submissionDAO.getCreatedBy(submission.getId());
    	});
    }

    @Test
    public void testGetCreatedBy(){
        String returnedId = submissionDAO.create(submission);
    	assertEquals(userId, submissionDAO.getCreatedBy(returnedId));
    }
    
	private static String createEntityBundleJSON(EntityBundle bundle) throws JSONObjectAdapterException {
		JSONObjectAdapter joa = new JSONObjectAdapterImpl();
		bundle.writeToJSONObject(joa);
		return joa.toJSONString();
	}
	
	private static String DOCKER_REPO_NAME = "docker.synapse.org/syn123/arepo";
	
	@Test
	public void testIsDockerRepoNameInAnyEvaluationWithAccess() throws Exception {
		// method under test
		// evaluation admin cannot yet access repo since it has not yet been submitted
		assertFalse(submissionDAO.isDockerRepoNameInAnyEvaluationWithAccess(DOCKER_REPO_NAME, 
				Collections.singleton(Long.parseLong(userId2)), ACCESS_TYPE.READ));

		Submission dockerSubmission = newSubmission(SUBMISSION_ID, userId, dockerNodeId, new Date(CREATION_TIME_STAMP));
		EntityBundle bundle = new EntityBundle();
		DockerRepository entity = new DockerRepository();
		entity.setRepositoryName(DOCKER_REPO_NAME);
		bundle.setEntity(entity);
		bundle.setFileHandles(Collections.EMPTY_LIST);
		dockerSubmission.setDockerRepositoryName(DOCKER_REPO_NAME);
		dockerSubmission.setEntityBundleJSON(createEntityBundleJSON(bundle));

		// create the submission
		String returnedId = submissionDAO.create(dockerSubmission);

		// method under test
		// now evaluation admin can access the repo
		assertTrue(submissionDAO.isDockerRepoNameInAnyEvaluationWithAccess(DOCKER_REPO_NAME, 
				Collections.singleton(Long.parseLong(userId)), ACCESS_TYPE.READ));

		// method under test
		// non-admin cannot access
		assertFalse(submissionDAO.isDockerRepoNameInAnyEvaluationWithAccess(DOCKER_REPO_NAME, 
				Collections.singleton(Long.parseLong(userId2)), ACCESS_TYPE.READ));

		// method under test
		// admin cannot access non-existent name
		assertFalse(submissionDAO.isDockerRepoNameInAnyEvaluationWithAccess("some-other-name", 
				Collections.singleton(Long.parseLong(userId)), ACCESS_TYPE.READ));

		// method under test
		// wrong access type
		assertFalse(submissionDAO.isDockerRepoNameInAnyEvaluationWithAccess(DOCKER_REPO_NAME, 
				Collections.singleton(Long.parseLong(userId)), ACCESS_TYPE.MODERATE));

		// method under test
		// fails gracefully if no principals are passed
		assertFalse(submissionDAO.isDockerRepoNameInAnyEvaluationWithAccess(DOCKER_REPO_NAME, 
				Collections.EMPTY_SET, ACCESS_TYPE.READ));


	}
	
	@Test
	public void testGetSubmissionIdAndEtag() {

		String subId1 = submissionDAO.create(submission);
		createSubmissionStatus(subId1, SubmissionStatusEnum.SCORED);
		String etag1 = submissionStatusDAO.get(subId1).getEtag();
		String subId2 = submissionDAO.create(submission2);
		createSubmissionStatus(subId2, SubmissionStatusEnum.SCORED);
		String etag2 = submissionStatusDAO.get(subId2).getEtag();
		String subId3 = submissionDAO.create(submission3);
		createSubmissionStatus(subId3, SubmissionStatusEnum.SCORED);
		String etag3 = submissionStatusDAO.get(subId3).getEtag();
		
		Long evaluationId = Long.valueOf(evalId);
		
		List<IdAndEtag> expected = ImmutableList.of(
			new IdAndEtag(Long.valueOf(subId1), etag1, evaluationId),
			new IdAndEtag(Long.valueOf(subId2), etag2, evaluationId),
			new IdAndEtag(Long.valueOf(subId3), etag3, evaluationId)
		);
		
		// Call under test
		List<IdAndEtag> result = submissionDAO.getSubmissionIdAndEtag(evaluationId);
		
		assertEquals(expected, result);
		
	}
	
	@Test
	public void testGetSubmissionIdAndEtagWithNullInput() {
		
		Long evaluationId = null;

		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			// Call under test
			submissionDAO.getSubmissionIdAndEtag(evaluationId);
			
		}).getMessage();
		
		assertEquals("evaluationId is required.", errorMessage);
		
	}
	
	@Test
	public void testGetSumOfSubmissionCRCsForEachEvaluation() {
		Long evaluationId1 = Long.valueOf(evalId);
		Long evaluationId2 = Long.valueOf(evalId2);
		
		// Creates 3 submissions for evalId
		submissionDAO.create(submission);
		createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.SCORED);
		submissionDAO.create(submission2);
		createSubmissionStatus(SUBMISSION_2_ID, SubmissionStatusEnum.SCORED);
		submissionDAO.create(submission3);
		createSubmissionStatus(SUBMISSION_3_ID, SubmissionStatusEnum.SCORED);
		
		List<Long> ids = ImmutableList.of(evaluationId1, evaluationId2);
		
		Map<Long, Long> result = submissionDAO.getSumOfSubmissionCRCsForEachEvaluation(ids);
		
		assertEquals(1L, result.size());
		assertNotNull(result.get(evaluationId1));
	}
	
	@Test
	public void testGetSumOfSubmissionCRCsForEachEvaluationWithNullInput() {
		
		List<Long> ids = null;
		
		String errorMessage = assertThrows(IllegalArgumentException.class, () -> {
			submissionDAO.getSumOfSubmissionCRCsForEachEvaluation(ids);
		}).getMessage();
		
		assertEquals("evaluationIds is required.", errorMessage);
		
	}
	
	@Test
	public void testGetSumOfSubmissionCRCsForEachEvaluationWithEmptyInput() {
		
		List<Long> ids = Collections.emptyList();
		
		Map<Long, Long> result = submissionDAO.getSumOfSubmissionCRCsForEachEvaluation(ids);
		
		assertTrue(result.isEmpty());
		
	}

	@Test
	public void testGetSubmissionData() {

		// Creates 3 submissions for evalId
		submissionDAO.create(submission);
		createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.SCORED);
		submissionDAO.create(submission2);
		createSubmissionStatus(SUBMISSION_2_ID, SubmissionStatusEnum.EVALUATION_IN_PROGRESS);
		submissionDAO.create(submission3);
		createSubmissionStatus(SUBMISSION_3_ID, SubmissionStatusEnum.RECEIVED);

		// Make a submission to the another evaluation
		Submission submission = newSubmission(SUBMISSION_4_ID, userId2, nodeId, new Date(CREATION_TIME_STAMP));
		submission.setEvaluationId(evalId2);

		submissionDAO.create(submission);

		String nonExistingId = "100000";

		List<Long> ids = ImmutableList.of(SUBMISSION_ID, SUBMISSION_2_ID, SUBMISSION_3_ID, nonExistingId).stream()
				.map(Long::valueOf).collect(Collectors.toList());

		int maxAnnotationChars = 500;

		// Call under test
		List<ObjectDataDTO> result = submissionDAO.getSubmissionData(ids, maxAnnotationChars);

		assertNotNull(result);
		assertEquals(3L, result.size());

		Map<String, ObjectDataDTO> map = result.stream()
				.collect(Collectors.toMap((v) -> v.getId().toString(), Function.identity()));

		assertFalse(map.containsKey(nonExistingId));

		verifyObjectData(map.get(SUBMISSION_ID));
		verifyObjectData(map.get(SUBMISSION_2_ID));
		verifyObjectData(map.get(SUBMISSION_3_ID));
	}
	
	@Test
	public void testGetSubmissionDataWithAnnotations() {

		Annotations annotations = AnnotationsV2Utils.emptyAnnotations();

		AnnotationsV2TestUtils.putAnnotations(annotations, "foo", "fooValue", AnnotationsValueType.STRING);
		AnnotationsV2TestUtils.putAnnotations(annotations, "bar", "42", AnnotationsValueType.LONG);

		// Creates 3 submissions for evalId
		submissionDAO.create(submission);
		createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.SCORED, annotations);

		List<Long> ids = ImmutableList.of(SUBMISSION_ID).stream()
				.map(Long::valueOf).collect(Collectors.toList());

		int maxAnnotationChars = 500;

		// Call under test
		List<ObjectDataDTO> result = submissionDAO.getSubmissionData(ids, maxAnnotationChars);

		assertNotNull(result);
		assertEquals(1L, result.size());

		Map<String, ObjectAnnotationDTO> annotationsMap = verifyObjectData(result.stream().findFirst().get());
		
		assertAnnotationValue("fooValue", AnnotationType.STRING, annotationsMap.get("foo"));
		assertAnnotationValue("42", AnnotationType.LONG, annotationsMap.get("bar"));
	}
	
	@Test
	public void testGetSubmissionDataWithAnnotationsOverride() {

		Annotations annotations = AnnotationsV2Utils.emptyAnnotations();

		AnnotationsV2TestUtils.putAnnotations(annotations, "foo", "fooValue", AnnotationsValueType.STRING);
		AnnotationsV2TestUtils.putAnnotations(annotations, "bar", "42", AnnotationsValueType.LONG);
		
		// This should not be indexed, as the status is a submission field
		AnnotationsV2TestUtils.putAnnotations(annotations, "status", "OVERRIDEN_STATUS", AnnotationsValueType.STRING);

		// Creates 3 submissions for evalId
		submissionDAO.create(submission);
		createSubmissionStatus(SUBMISSION_ID, SubmissionStatusEnum.SCORED, annotations);

		List<Long> ids = ImmutableList.of(SUBMISSION_ID).stream()
				.map(Long::valueOf).collect(Collectors.toList());

		int maxAnnotationChars = 500;

		// Call under test
		List<ObjectDataDTO> result = submissionDAO.getSubmissionData(ids, maxAnnotationChars);

		assertNotNull(result);
		assertEquals(1L, result.size());

		Map<String, ObjectAnnotationDTO> annotationsMap = verifyObjectData(result.stream().findFirst().get());
		
		assertAnnotationValue("fooValue", AnnotationType.STRING, annotationsMap.get("foo"));
		assertAnnotationValue("42", AnnotationType.LONG, annotationsMap.get("bar"));
	}

	@Test
	public void testHasSubmissionForEvaluationRound(){
		Instant now = Instant.now();
		EvaluationRound evaluationRound = new EvaluationRound();
		evaluationRound.setId("2020");
		evaluationRound.setEvaluationId(evalId);
		evaluationRound.setRoundStart(Date.from(now));
		evaluationRound.setRoundEnd(Date.from(now.plus(10, ChronoUnit.DAYS)));
		evaluationRound = evaluationDAO.createEvaluationRound(evaluationRound);

		//no associated submissions yet
		assertFalse(submissionDAO.hasSubmissionForEvaluationRound(evalId, evaluationRound.getId()));

		submission.setEvaluationRoundId(evaluationRound.getId());
		String submissionId = submissionDAO.create(submission);

		assertTrue(submissionDAO.hasSubmissionForEvaluationRound(evalId, evaluationRound.getId()));

		submissionDAO.delete(submissionId);
		assertFalse(submissionDAO.hasSubmissionForEvaluationRound(evalId, evaluationRound.getId()));

	}

	private Map<String, ObjectAnnotationDTO> verifyObjectData(ObjectDataDTO data) {
		String submissionId = data.getId().toString();

		Submission submission = submissionDAO.get(submissionId);
		Evaluation evaluation = evaluationDAO.get(submission.getEvaluationId());
		SubmissionStatus status = submissionStatusDAO.get(submissionId);

		assertEquals(status.getEtag(), data.getEtag());
		assertEquals(status.getVersionNumber(), data.getVersion());
		assertEquals(submission.getEvaluationId(), data.getParentId().toString());
		assertEquals(submission.getEvaluationId(), data.getBenefactorId().toString());
		assertEquals(evaluation.getContentSource(), KeyFactory.keyToString(data.getProjectId()));
		assertEquals(submission.getName(), data.getName());
		assertEquals(SubType.submission, data.getSubType());
		assertNotNull(data.getAnnotations());

		Map<String, ObjectAnnotationDTO> annotations = data.getAnnotations().stream()
				.collect(Collectors.toMap(ObjectAnnotationDTO::getKey, Function.identity()));

		for (SubmissionField field : SubmissionField.values()) {
			ObjectAnnotationDTO fieldAnnotation = annotations.get(field.getColumnName());
			if (fieldAnnotation == null) {
				assertTrue(field.isNullable(), "No annotation found and the field was not nullable");
			} else {
				assertEquals(submissionId, fieldAnnotation.getObjectId().toString());
				assertEquals(status.getVersionNumber(), fieldAnnotation.getObjectVersion());
				assertEquals(field.getAnnotationType(), fieldAnnotation.getType());
				assertFalse(fieldAnnotation.getValue().isEmpty());
			}
		}

		assertAnnotationValue(annotations, SubmissionField.entityid, KeyFactory.stringToKey(submission.getEntityId()).toString());
		assertAnnotationValue(annotations, SubmissionField.entityversion, submission.getVersionNumber().toString());
		assertAnnotationValue(annotations, SubmissionField.evaluationid, submission.getEvaluationId());
		assertAnnotationValue(annotations, SubmissionField.dockerrepositoryname, submission.getDockerRepositoryName());
		assertAnnotationValue(annotations, SubmissionField.dockerdigest, submission.getDockerDigest());
		assertAnnotationValue(annotations, SubmissionField.submitteralias, submission.getSubmitterAlias());
		assertAnnotationValue(annotations, SubmissionField.status, status.getStatus().name());
		assertAnnotationValue(annotations, SubmissionField.submitterid, submission.getTeamId() == null ? submission.getUserId() : submission.getTeamId());
		
		return annotations;
	}

	private void assertAnnotationValue(Map<String, ObjectAnnotationDTO> annotations, SubmissionField field, String expectedValue) {
		ObjectAnnotationDTO annotation = annotations.get(field.getColumnName());
		assertAnnotationValue(expectedValue, field.getAnnotationType(), annotation);
	}
	
	private void assertAnnotationValue(String expectedValue, AnnotationType annotationType, ObjectAnnotationDTO annotation) {
		if (expectedValue == null) {
			assertNull(annotation);
		} else {
			assertFalse(annotation.getValue().isEmpty());
			assertEquals(expectedValue, annotation.getValue().iterator().next());
			assertEquals(annotationType, annotation.getType());
		}
	}
	
}
