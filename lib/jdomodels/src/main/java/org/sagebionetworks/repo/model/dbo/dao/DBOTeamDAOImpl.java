/**
 * 
 */
package org.sagebionetworks.repo.model.dbo.dao;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.*;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_GROUP_MEMBERS_MEMBER_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_RESOURCE_ACCESS_GROUP_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_RESOURCE_ACCESS_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_RESOURCE_ACCESS_OWNER;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_RESOURCE_ACCESS_TYPE_ELEMENT;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_RESOURCE_ACCESS_TYPE_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_TEAM_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_TEAM_PROPERTIES;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_USER_PROFILE_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_USER_PROFILE_PROPS_BLOB;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.LIMIT_PARAM_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.OFFSET_PARAM_NAME;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_GROUP_MEMBERS;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_RESOURCE_ACCESS;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_RESOURCE_ACCESS_TYPE;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_TEAM;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_USER_PROFILE;

import java.sql.Blob;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.sagebionetworks.ids.ETagGenerator;
import org.sagebionetworks.ids.IdGenerator;
import org.sagebionetworks.repo.model.ACCESS_TYPE;
import org.sagebionetworks.repo.model.ConflictingUpdateException;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.Team;
import org.sagebionetworks.repo.model.TeamDAO;
import org.sagebionetworks.repo.model.TeamHeader;
import org.sagebionetworks.repo.model.TeamMember;
import org.sagebionetworks.repo.model.UserGroupHeader;
import org.sagebionetworks.repo.model.UserProfile;
import org.sagebionetworks.repo.model.dbo.DBOBasicDao;
import org.sagebionetworks.repo.model.dbo.persistence.DBOTeam;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @author brucehoff
 *
 */
public class DBOTeamDAOImpl implements TeamDAO {

	@Autowired
	private DBOBasicDao basicDao;	
	@Autowired
	private IdGenerator idGenerator;
	@Autowired
	private ETagGenerator eTagGenerator;
	@Autowired
	private SimpleJdbcTemplate simpleJdbcTemplate;

	private static final RowMapper<DBOTeam> teamRowMapper = (new DBOTeam()).getTableMapping();
	
	private static final String SELECT_PAGINATED = 
			"SELECT t.*, g."+COL_USER_GROUP_NAME+" FROM "+TABLE_USER_GROUP+" g, "+TABLE_TEAM+" t "+
			" WHERE t."+COL_TEAM_ID+"=gm."+COL_USER_GROUP_ID+" order by "+COL_USER_GROUP_NAME+" ascending "+
			" LIMIT :"+LIMIT_PARAM_NAME+" OFFSET :"+OFFSET_PARAM_NAME;
	
	private static final String SELECT_COUNT = 
			"SELECT COUNT(*) FROM "+TABLE_TEAM;

	private static final String SELECT_FOR_MEMBER_SQL_CORE = 
			" FROM "+TABLE_GROUP_MEMBERS+" gm, "+TABLE_TEAM+" t "+
			" WHERE t."+COL_TEAM_ID+"=gm."+COL_GROUP_MEMBERS_GROUP_ID+" AND "+
			" gm."+COL_GROUP_MEMBERS_MEMBER_ID+" IN (:"+COL_GROUP_MEMBERS_MEMBER_ID+")";

	private static final String SELECT_FOR_MEMBER_PAGINATED = 
			"SELECT t.* "+SELECT_FOR_MEMBER_SQL_CORE+
			" LIMIT :"+LIMIT_PARAM_NAME+" OFFSET :"+OFFSET_PARAM_NAME;
	
	private static final String SELECT_FOR_MEMBER_COUNT = 
			"SELECT count(*) "+SELECT_FOR_MEMBER_SQL_CORE;

	private static final String USER_PROFILE_PROPERTIES_COLUMN_LABEL = "USER_PROFILE_PROPERTIES";

	private static final String SELECT_ALL_TEAMS_AND_MEMBERS =
			"SELECT t.*, up."+COL_USER_PROFILE_PROPS_BLOB+" as "+USER_PROFILE_PROPERTIES_COLUMN_LABEL+", up."+COL_USER_PROFILE_ID+
			" FROM "+TABLE_TEAM+" t, "+TABLE_GROUP_MEMBERS+" gm LEFT OUTER JOIN "+
			TABLE_USER_PROFILE+" up ON (gm."+COL_GROUP_MEMBERS_MEMBER_ID+"=up."+COL_USER_PROFILE_ID+") "+
			" WHERE t."+COL_TEAM_ID+"=gm."+COL_GROUP_MEMBERS_GROUP_ID;
	
	private static final String SELECT_ALL_TEAMS_AND_ADMIN_MEMBERS =
			"SELECT t."+COL_TEAM_ID+", gm."+COL_GROUP_MEMBERS_MEMBER_ID+" FROM "+
				TABLE_TEAM+" t, "+
				TABLE_RESOURCE_ACCESS+" ra, "+TABLE_RESOURCE_ACCESS_TYPE+" at, "+
				TABLE_GROUP_MEMBERS+" gm "+
			" WHERE t."+COL_TEAM_ID+"=gm."+COL_GROUP_MEMBERS_GROUP_ID+
			" and ra."+COL_RESOURCE_ACCESS_GROUP_ID+"=gm."+COL_GROUP_MEMBERS_MEMBER_ID+
			" and ra."+COL_RESOURCE_ACCESS_OWNER+"=gm."+COL_GROUP_MEMBERS_GROUP_ID+
			" and at."+COL_RESOURCE_ACCESS_TYPE_ID+"=ra."+COL_RESOURCE_ACCESS_ID+
			" and at."+COL_RESOURCE_ACCESS_TYPE_ELEMENT+"='"+ACCESS_TYPE.TEAM_MEMBERSHIP_UPDATE+"'";
	
	private static final String SELECT_FOR_UPDATE_SQL = "select * from "+TABLE_TEAM+" where "+COL_TEAM_ID+
			"=:"+COL_TEAM_ID+" for update";

	/* (non-Javadoc)
	 * @see org.sagebionetworks.repo.model.TeamDAO#create(org.sagebionetworks.repo.model.Team)
	 */
	@Transactional(readOnly = false, propagation = Propagation.REQUIRED)
	@Override
	public Team create(Team dto) throws DatastoreException,
	InvalidModelException {
		if (dto.getId()==null) throw new InvalidModelException("ID is required");
		DBOTeam dbo = new DBOTeam();
		TeamUtils.copyDtoToDbo(dto, dbo);
		dbo.setEtag(eTagGenerator.generateETag());
		dbo = basicDao.createNew(dbo);
		Team result = TeamUtils.copyDboToDto(dbo);
		return result;
	}

	/* (non-Javadoc)
	 * @see org.sagebionetworks.repo.model.TeamDAO#get(java.lang.String)
	 */
	@Override
	public Team get(String id) throws DatastoreException, NotFoundException {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue(COL_TEAM_ID.toLowerCase(), id);
		DBOTeam dbo = basicDao.getObjectByPrimaryKey(DBOTeam.class, param);
		Team dto = TeamUtils.copyDboToDto(dbo);
		return dto;
	}

	/* (non-Javadoc)
	 * @see org.sagebionetworks.repo.model.TeamDAO#getInRange(long, long)
	 */
	@Override
	public List<Team> getInRange(long limit, long offset)
			throws DatastoreException {
		MapSqlParameterSource param = new MapSqlParameterSource();	
		param.addValue(OFFSET_PARAM_NAME, offset);
		if (limit<=0) throw new IllegalArgumentException("'to' param must be greater than 'from' param.");
		param.addValue(LIMIT_PARAM_NAME, limit);	
		List<DBOTeam> dbos = simpleJdbcTemplate.query(SELECT_PAGINATED, teamRowMapper, param);
		List<Team> dtos = new ArrayList<Team>();
		for (DBOTeam dbo : dbos) dtos.add(TeamUtils.copyDboToDto(dbo));
		return dtos;
	}

	@Override
	public long getCount() throws DatastoreException {
		return simpleJdbcTemplate.queryForLong(SELECT_COUNT);
	}

	/* (non-Javadoc)
	 * @see org.sagebionetworks.repo.model.TeamDAO#getForMemberInRange(java.lang.String, long, long)
	 */
	@Override
	public List<Team> getForMemberInRange(String principalId,
			long limit, long offset) throws DatastoreException {
		MapSqlParameterSource param = new MapSqlParameterSource();	
		param.addValue(COL_GROUP_MEMBERS_MEMBER_ID, principalId);
		param.addValue(OFFSET_PARAM_NAME, offset);
		if (limit<=0) throw new IllegalArgumentException("'to' param must be greater than 'from' param.");
		param.addValue(LIMIT_PARAM_NAME, limit);	
		List<DBOTeam> dbos = simpleJdbcTemplate.query(SELECT_FOR_MEMBER_PAGINATED, teamRowMapper, param);
		List<Team> dtos = new ArrayList<Team>();
		for (DBOTeam dbo : dbos) dtos.add(TeamUtils.copyDboToDto(dbo));
		return dtos;
	}

	@Override
	public long getCountForMember(String principalId) throws DatastoreException {
		MapSqlParameterSource param = new MapSqlParameterSource();	
		param.addValue(COL_GROUP_MEMBERS_MEMBER_ID, principalId);
		return simpleJdbcTemplate.queryForLong(SELECT_FOR_MEMBER_COUNT, param);
	}

	/* (non-Javadoc)
	 * @see org.sagebionetworks.repo.model.TeamDAO#update(org.sagebionetworks.repo.model.Team)
	 */
	@Override
	public Team update(Team dto) throws InvalidModelException,
			NotFoundException, ConflictingUpdateException, DatastoreException {

		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue(COL_TEAM_ID, dto.getId());
		DBOTeam dbo = null;
		try{
			dbo = simpleJdbcTemplate.queryForObject(SELECT_FOR_UPDATE_SQL, teamRowMapper, param);
		}catch (EmptyResultDataAccessException e) {
			throw new NotFoundException("The resource you are attempting to access cannot be found");
		}
		
		String oldEtag = dbo.getEtag();
		// check dbo's etag against dto's etag
		// if different rollback and throw a meaningful exception
		if (!oldEtag.equals(dto.getEtag())) {
			throw new ConflictingUpdateException("Use profile was updated since you last fetched it, retrieve it again and reapply the update.");
		}

		{
			Team deserializedProperties = TeamUtils.copyFromSerializedField(dbo);
			if (dto.getCreatedBy()==null) dto.setCreatedBy(deserializedProperties.getCreatedBy());
			if (dto.getCreatedOn()==null) dto.setCreatedOn(deserializedProperties.getCreatedOn());
			if (!dto.getName().equals(deserializedProperties.getName())) throw new InvalidModelException("Cannot modify team name.");
		}
		
		TeamUtils.copyDtoToDbo(dto, dbo);
		// Update with a new e-tag
		dbo.setEtag(eTagGenerator.generateETag());

		boolean success = basicDao.update(dbo);
		if (!success) throw new DatastoreException("Unsuccessful updating Team in database.");

		Team resultantDto = TeamUtils.copyDboToDto(dbo);
		return resultantDto;
	}

	/* (non-Javadoc)
	 * @see org.sagebionetworks.repo.model.TeamDAO#delete(java.lang.String)
	 */
	@Override
	public void delete(String id) throws DatastoreException, NotFoundException {
		MapSqlParameterSource param = new MapSqlParameterSource();
		param.addValue(COL_TEAM_ID.toLowerCase(), id);
		basicDao.deleteObjectByPrimaryKey(DBOTeam.class, param);
	}

	public static class TeamMemberPair {
		private TeamHeader teamHeader;
		private TeamMember teamMember;
		public TeamHeader getTeamHeader() {
			return teamHeader;
		}
		public void setTeamHeader(TeamHeader teamHeader) {
			this.teamHeader = teamHeader;
		}
		public TeamMember getTeamMember() {
			return teamMember;
		}
		public void setTeamMember(TeamMember teamMember) {
			this.teamMember = teamMember;
		}

	}
	
	private static final RowMapper<TeamMemberPair> teamMemberPairRowMapper = new RowMapper<TeamMemberPair>(){
		@Override
		public TeamMemberPair mapRow(ResultSet rs, int rowNum) throws SQLException {
			TeamMemberPair tmp = new TeamMemberPair();
			TeamHeader teamHeader = new TeamHeader();
			tmp.setTeamHeader(teamHeader);
			teamHeader.setId(rs.getString(COL_TEAM_ID));
			{
				Blob teamProperties = rs.getBlob(COL_TEAM_PROPERTIES);
				Team team = TeamUtils.deserialize(teamProperties.getBytes(1, (int) teamProperties.length()));
				teamHeader.setName(team.getName());
			}
			{
				UserGroupHeader ugh = new UserGroupHeader();
				TeamMember tm = new TeamMember();
				tm.setMember(ugh);
				tm.setTeamId(teamHeader.getId());
				tm.setIsAdmin(false);
				tmp.setTeamMember(tm);
				Blob upProperties = rs.getBlob(USER_PROFILE_PROPERTIES_COLUMN_LABEL);
				if (upProperties!=null) {
					ugh.setIsIndividual(true);
					ugh.setOwnerId(rs.getString(COL_USER_PROFILE_ID));
					UserProfile up = UserProfileUtils.deserialize(upProperties.getBytes(1, (int) upProperties.length()));
					ugh.setDisplayName(up.getDisplayName());
					ugh.setFirstName(up.getFirstName());
					ugh.setLastName(up.getLastName());
					ugh.setPic(up.getPic());
					ugh.setEmail(up.getEmail());
				} else {
					ugh.setIsIndividual(false);
				}
			}
			return tmp;
		}
	};
	
	public static class TeamMemberId {
		private Long teamId;
		public Long getTeamId() {
			return teamId;
		}
		public void setTeamId(Long teamId) {
			this.teamId = teamId;
		}
		public Long getMemberId() {
			return memberId;
		}
		public void setMemberId(Long memberId) {
			this.memberId = memberId;
		}
		private Long memberId;
	}
	
	private static final RowMapper<TeamMemberId> teamMemberIdRowMapper = new RowMapper<TeamMemberId>(){
		@Override
		public TeamMemberId mapRow(ResultSet rs, int rowNum) throws SQLException {
			TeamMemberId tmi = new TeamMemberId();
			tmi.setTeamId(rs.getLong(COL_TEAM_ID));
			tmi.setMemberId(rs.getLong(COL_GROUP_MEMBERS_MEMBER_ID));
			return tmi;
		}
	};

	@Override
	public Map<TeamHeader, Collection<TeamMember>> getAllTeamsAndMembers() throws DatastoreException {
		// first get all the Teams and Members, regardless of whether the members are administrators
		List<TeamMemberPair> queryResults = simpleJdbcTemplate.query(SELECT_ALL_TEAMS_AND_MEMBERS, teamMemberPairRowMapper);
		Map<Long, TeamHeader> teamHeaderMap = new HashMap<Long, TeamHeader>();
		Map<Long, Map<Long,TeamMember>> teamMemberMap = new HashMap<Long, Map<Long,TeamMember>>();
		for (TeamMemberPair tmp : queryResults) {
			long teamHeaderId = Long.parseLong(tmp.getTeamHeader().getId());
			teamHeaderMap.put(teamHeaderId, tmp.getTeamHeader());
			Map<Long,TeamMember> tms = teamMemberMap.get(teamHeaderId);
			if (tms==null) {
				tms = new HashMap<Long,TeamMember>();
				teamMemberMap.put(teamHeaderId, tms);
			}
			tms.put(Long.parseLong(tmp.getTeamMember().getMember().getOwnerId()), tmp.getTeamMember());
		}
		// second, get the team, member pairs for which the member is an administrator of the team
		List<TeamMemberId> adminTeamMembers = simpleJdbcTemplate.query(SELECT_ALL_TEAMS_AND_ADMIN_MEMBERS, teamMemberIdRowMapper);
		for (TeamMemberId tmi : adminTeamMembers) {
			// since the admin's are a subset of the entire <team,member> universe, we *must* find them in the map
			Map<Long,TeamMember> members = teamMemberMap.get(tmi.getTeamId());
			if (members==null) throw new IllegalStateException("No members found for team ID: "+tmi.getTeamId());
			TeamMember tm = members.get(tmi.getMemberId());
			if (tm==null) throw new IllegalStateException("No member found for team ID: "+tmi.getTeamId()+", member ID: "+tmi.getMemberId());
			tm.setIsAdmin(true);
		}
		Map<TeamHeader, Collection<TeamMember>> results = new HashMap<TeamHeader, Collection<TeamMember>>();
		// finally, create the results to return
		for (Long teamId : teamHeaderMap.keySet()) {
			TeamHeader teamHeader = teamHeaderMap.get(teamId);
			if (teamHeader==null) throw new IllegalStateException("Missing TeamHeader for team ID: "+teamId);
			Collection<TeamMember> teamMembers = teamMemberMap.get(teamId).values();
			if (teamMembers.isEmpty()) throw new IllegalStateException("Missing team members for team ID :"+teamId);
			results.put(teamHeader, teamMembers);
		}
		return results;
	}

}
