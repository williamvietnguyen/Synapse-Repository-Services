package org.sagebionetworks.repo.model.dbo.persistence;

import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_ACTIVITY_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_COLUMN_MODEL_IDS;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_COMMENT;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_ENTITY_PROPERTY_ANNOTATIONS_BLOB;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_FILE_HANDLE_ID;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_ITEMS;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_LABEL;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_MODIFIED_BY;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_MODIFIED_ON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_NUMBER;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_OWNER_NODE;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_REF_BLOB;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_SCOPE_IDS;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.COL_REVISION_USER_ANNOS_JSON;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.DDL_FILE_REVISION;
import static org.sagebionetworks.repo.model.query.jdo.SqlConstants.TABLE_REVISION;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.sagebionetworks.repo.model.dbo.FieldColumn;
import org.sagebionetworks.repo.model.dbo.MigratableDatabaseObject;
import org.sagebionetworks.repo.model.dbo.TableMapping;
import org.sagebionetworks.repo.model.dbo.migration.BasicMigratableTableTranslation;
import org.sagebionetworks.repo.model.dbo.migration.MigratableTableTranslation;
import org.sagebionetworks.repo.model.migration.MigrationType;

/**
 * The DatabaseObject for Revision.
 * 
 * @author jmhill
 *
 */
public class DBORevision implements MigratableDatabaseObject<DBORevision, DBORevision> {
	public static final int MAX_COMMENT_LENGTH = 256;

	static final MigratableTableTranslation<DBORevision, DBORevision> TRANSLATOR = new BasicMigratableTableTranslation<DBORevision>();
	
	private static FieldColumn[] FIELDS = new FieldColumn[] {
		// This is a sub-table of node, so it gets backed up with nodes using the node ids
		// so its backup ID is the owner.
		new FieldColumn("owner", COL_REVISION_OWNER_NODE, true).withIsBackupId(true),
		new FieldColumn("revisionNumber", COL_REVISION_NUMBER, true),
		new FieldColumn("activityId", COL_REVISION_ACTIVITY_ID),
		new FieldColumn("label", COL_REVISION_LABEL),
		new FieldColumn("comment", COL_REVISION_COMMENT),
		new FieldColumn("modifiedBy", COL_REVISION_MODIFIED_BY),
		new FieldColumn("modifiedOn", COL_REVISION_MODIFIED_ON),
		new FieldColumn("fileHandleId", COL_REVISION_FILE_HANDLE_ID).withHasFileHandleRef(true),
		new FieldColumn("columnModelIds", COL_REVISION_COLUMN_MODEL_IDS),
		new FieldColumn("scopeIds", COL_REVISION_SCOPE_IDS),
		new FieldColumn("items", COL_REVISION_ITEMS),
		new FieldColumn("entityPropertyAnnotations", COL_REVISION_ENTITY_PROPERTY_ANNOTATIONS_BLOB),
		new FieldColumn("reference", COL_REVISION_REF_BLOB),
		new FieldColumn("userAnnotationsJSON", COL_REVISION_USER_ANNOS_JSON)
		};

	@Override
	public TableMapping<DBORevision> getTableMapping() {
		return new TableMapping<DBORevision>(){
			@Override
			public DBORevision mapRow(ResultSet rs, int rowNum)	throws SQLException {
				boolean includeAnnotations = true;
				DBORevisionMapper mapper = new DBORevisionMapper(includeAnnotations);
				return mapper.mapRow(rs, rowNum);
			}

			@Override
			public String getTableName() {
				return TABLE_REVISION;
			}

			@Override
			public String getDDLFileName() {
				return DDL_FILE_REVISION;
			}

			@Override
			public FieldColumn[] getFieldColumns() {
				return FIELDS;
			}

			@Override
			public Class<? extends DBORevision> getDBOClass() {
				return DBORevision.class;
			}};
	}
	
	private Long owner;
	private Long revisionNumber;
	private Long activityId;
	private String label;
	private String comment;
	private Long modifiedBy;
	private Long modifiedOn;
	private Long fileHandleId;
	private byte[] columnModelIds;
	private byte[] scopeIds;
	private String items;
	private byte[] entityPropertyAnnotations;
	private byte[] reference;
	private String userAnnotationsJSON;
	// used for migration only

	public Long getOwner() {
		return owner;
	}
	public void setOwner(Long owner) {
		this.owner = owner;
	}
	public Long getRevisionNumber() {
		return revisionNumber;
	}
	public void setRevisionNumber(Long revisionNumber) {
		this.revisionNumber = revisionNumber;
	}
	public String getLabel() {
		return label;
	}
	public void setLabel(String label) {
		this.label = label;
	}
	public String getComment() {
		return comment;
	}
	public void setComment(String comment) {
		this.comment = comment;
	}
	public Long getModifiedBy() {
		return modifiedBy;
	}
	public void setModifiedBy(Long modifiedBy) {
		this.modifiedBy = modifiedBy;
	}
	public Long getModifiedOn() {
		return modifiedOn;
	}
	public void setModifiedOn(Long modifiedOn) {
		this.modifiedOn = modifiedOn;
	}
	public byte[] getReference() {
		return reference;
	}
	public void setReference(byte[] reference) {
		this.reference = reference;
	}	
	public Long getActivityId() {
		return activityId;
	}
	public void setActivityId(Long activityId) {
		this.activityId = activityId;
	}
	
	public byte[] getColumnModelIds() {
		return columnModelIds;
	}
	public void setColumnModelIds(byte[] columnModelIds) {
		this.columnModelIds = columnModelIds;
	}
	
	public byte[] getScopeIds() {
		return scopeIds;
	}
	public void setScopeIds(byte[] scopeIds) {
		this.scopeIds = scopeIds;
	}
	public Long getFileHandleId() {
		return fileHandleId;
	}
	public void setFileHandleId(Long fileHandleId) {
		this.fileHandleId = fileHandleId;
	}

	public byte[] getEntityPropertyAnnotations() {
		return entityPropertyAnnotations;
	}

	public void setEntityPropertyAnnotations(byte[] entityPropertyAnnotations) {
		this.entityPropertyAnnotations = entityPropertyAnnotations;
	}

	public String getUserAnnotationsJSON() {
		return userAnnotationsJSON;
	}

	public void setUserAnnotationsJSON(String userAnnotationsJSON) {
		this.userAnnotationsJSON = userAnnotationsJSON;
	}

	@Override
	public MigrationType getMigratableTableType() {
		return MigrationType.NODE_REVISION;
	}
	@Override
	public MigratableTableTranslation<DBORevision, DBORevision> getTranslator() {
		return TRANSLATOR;
	}
	
	@Override
	public Class<? extends DBORevision> getBackupClass() {
		return DBORevision.class;
	}
	@Override
	public Class<? extends DBORevision> getDatabaseObjectClass() {
		return DBORevision.class;
	}
	
	@Override
	public List<MigratableDatabaseObject<?,?>> getSecondaryTypes() {
		return null;
	}

	/**
	 * @return the items
	 */
	public String getItems() {
		return items;
	}
	/**
	 * @param items the items to set
	 */
	public void setItems(String items) {
		this.items = items;
	}
	
	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (!(obj instanceof DBORevision)) {
			return false;
		}
		DBORevision other = (DBORevision) obj;
		return Objects.equals(activityId, other.activityId) && Arrays.equals(columnModelIds, other.columnModelIds)
				&& Objects.equals(comment, other.comment)
				&& Arrays.equals(entityPropertyAnnotations, other.entityPropertyAnnotations)
				&& Objects.equals(fileHandleId, other.fileHandleId) && Objects.equals(items, other.items)
				&& Objects.equals(label, other.label) && Objects.equals(modifiedBy, other.modifiedBy)
				&& Objects.equals(modifiedOn, other.modifiedOn) && Objects.equals(owner, other.owner)
				&& Arrays.equals(reference, other.reference) && Objects.equals(revisionNumber, other.revisionNumber)
				&& Arrays.equals(scopeIds, other.scopeIds)
				&& Objects.equals(userAnnotationsJSON, other.userAnnotationsJSON);
	}

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
		result = prime * result + Arrays.hashCode(columnModelIds);
		result = prime * result + Arrays.hashCode(entityPropertyAnnotations);
		result = prime * result + Arrays.hashCode(reference);
		result = prime * result + Arrays.hashCode(scopeIds);
		result = prime * result + Objects.hash(activityId, comment, fileHandleId, items, label, modifiedBy, modifiedOn,
				owner, revisionNumber, userAnnotationsJSON);
		return result;
	}

	@Override
	public String toString() {
		return "DBORevision [owner=" + owner + ", revisionNumber=" + revisionNumber + ", activityId=" + activityId
				+ ", label=" + label + ", comment=" + comment + ", modifiedBy=" + modifiedBy + ", modifiedOn="
				+ modifiedOn + ", fileHandleId=" + fileHandleId + ", columnModelIds=" + Arrays.toString(columnModelIds)
				+ ", scopeIds=" + Arrays.toString(scopeIds) + ", items=" + items + ", entityPropertyAnnotations="
				+ Arrays.toString(entityPropertyAnnotations) + ", reference=" + Arrays.toString(reference)
				+ ", userAnnotationsJSON=" + userAnnotationsJSON + "]";
	}
}
