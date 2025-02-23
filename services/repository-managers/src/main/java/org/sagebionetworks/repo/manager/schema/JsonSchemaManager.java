package org.sagebionetworks.repo.manager.schema;

import java.util.Iterator;

import org.sagebionetworks.repo.model.AccessControlList;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.schema.BoundObjectType;
import org.sagebionetworks.repo.model.schema.CreateOrganizationRequest;
import org.sagebionetworks.repo.model.schema.CreateSchemaRequest;
import org.sagebionetworks.repo.model.schema.CreateSchemaResponse;
import org.sagebionetworks.repo.model.schema.JsonSchema;
import org.sagebionetworks.repo.model.schema.JsonSchemaObjectBinding;
import org.sagebionetworks.repo.model.schema.JsonSchemaVersionInfo;
import org.sagebionetworks.repo.model.schema.ListJsonSchemaInfoRequest;
import org.sagebionetworks.repo.model.schema.ListJsonSchemaInfoResponse;
import org.sagebionetworks.repo.model.schema.ListJsonSchemaVersionInfoRequest;
import org.sagebionetworks.repo.model.schema.ListJsonSchemaVersionInfoResponse;
import org.sagebionetworks.repo.model.schema.ListOrganizationsRequest;
import org.sagebionetworks.repo.model.schema.ListOrganizationsResponse;
import org.sagebionetworks.repo.model.schema.Organization;
import org.sagebionetworks.workers.util.aws.message.RecoverableMessageException;

/**
 * 
 * Business logic for the user defined schemas.
 *
 */
public interface JsonSchemaManager {
	
	public static final String ABSOLUTE_$ID_TEMPALTE = "https://repo-prod.prod.sagebase.org/repo/v1/schema/type/registered/%s";
	
	/**
	 * Create the full absolute $id from the relative $id
	 * @param $id
	 * @return
	 */
	public static String createAbsolute$id(String relative$id) {
		return String.format(ABSOLUTE_$ID_TEMPALTE, relative$id);
	}
	
	/**
	 * Create a new Organization from the given request
	 * @param user
	 * @param request
	 * @return
	 */
	Organization createOrganziation(UserInfo user, CreateOrganizationRequest request);
	
	/**
	 * Get the current ACL for the identified organization.
	 * 
	 * @param user
	 * @param organziationId
	 * @return
	 */
	public AccessControlList getOrganizationAcl(UserInfo user, String organziationId);
	
	/**
	 * Update the ACL for the identified organization.
	 * 
	 * @param user
	 * @param organziationId
	 * @return
	 */
	public AccessControlList updateOrganizationAcl(UserInfo user, String organziationId, AccessControlList acl);

	/**
	 * Delete the identified Organization.
	 * @param user
	 * @param id
	 */
	void deleteOrganization(UserInfo user, String id);

	/**
	 * Lookup an Organization by name.
	 * @param user
	 * @param name
	 * @return
	 */
	Organization getOrganizationByName(UserInfo user, String name);
	
	/**
	 * Create a new JsonSchema.
	 * @param user
	 * @param request
	 * @return
	 */
	CreateSchemaResponse createJsonSchema(UserInfo user, CreateSchemaRequest request) throws RecoverableMessageException;

	/**
	 * Get the JSON schema for a given $id
	 * @param $id
	 * @param isTopLevel
	 * @return
	 */
	JsonSchema getSchema(String $id, boolean isTopLevel);

	void truncateAll();

	/**
	 * Get the latest version of the given schema.
	 * @param user
	 * @param organizationName
	 * @param schemaName
	 * @return
	 */
	JsonSchemaVersionInfo getLatestVersion(String organizationName, String schemaName);
	
	/**
	 * List a single page of organizations.
	 * @param request
	 * @return
	 */
	ListOrganizationsResponse listOrganizations(ListOrganizationsRequest request);
	
	/**
	 * List a single page of schemas for an organization.
	 * @param request
	 * @return
	 */
	ListJsonSchemaInfoResponse listSchemas(ListJsonSchemaInfoRequest request);
	
	/**
	 * List a single page of schema versions for an organization and schema.
	 * @param request
	 * @return
	 */
	ListJsonSchemaVersionInfoResponse listSchemaVersions(ListJsonSchemaVersionInfoRequest request);

	/**
	 * Bind a JSON schema to an object.
	 * @param createdBy
	 * @param $id
	 * @param objectId
	 * @param objectType
	 * @return
	 */
	JsonSchemaObjectBinding bindSchemaToObject(Long createdBy, String $id, Long objectId, BoundObjectType objectType);

	/**
	 * Get the JsonSchemaObjectBinding for the given objectId and objectType 
	 * @param objectId
	 * @param objectType
	 * @return
	 */
	JsonSchemaObjectBinding getJsonSchemaObjectBinding(Long objectId, BoundObjectType objectType);

	/**
	 * Clear the bound schema from an object.
	 * @param objectId
	 * @param objectType
	 */
	void clearBoundSchema(Long objectId, BoundObjectType objectType);

	/**
	 * Delete a schema for the given $id.
	 * @param user
	 * @param $id
	 */
	void deleteSchemaById(UserInfo user, String $id);

	/**
	 * Creates a validation JSON schema for the given versionId, indexes it in the validation schema index,
	 * and sends out notifications to entities bound to it.
	 * @param versionId
	 * @return
	 */
	JsonSchema createOrUpdateValidationSchemaIndex(String versionId);

	/**
	 * Gets the validation schema for the given $id
	 * @param versionId
	 * @return
	 */
	JsonSchema getValidationSchema(String $id);
	
	/**
	 * Gets an iterator of the version IDs of all the schemas that reference the given schemaId
	 * and recursively the schemas that reference those schemas.
	 * @param schemaId
	 * @return
	 */
	Iterator<String> getVersionIdsOfDependantsIterator(String schemaId);

	/**
	 * Sends update notifications to all schemas that reference the given schema associated to the versionId
	 * and recursively to all the schemas that reference those schemas.
	 * @param versionId
	 */
	void sendUpdateNotificationsForDependantSchemas(String versionId);
}
