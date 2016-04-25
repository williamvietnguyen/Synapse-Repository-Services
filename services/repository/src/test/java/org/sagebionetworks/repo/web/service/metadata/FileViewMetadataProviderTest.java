package org.sagebionetworks.repo.web.service.metadata;

import static org.mockito.Mockito.*;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.sagebionetworks.repo.manager.table.FileViewManager;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.table.FileView;
import org.springframework.test.util.ReflectionTestUtils;

import com.google.common.collect.Lists;

public class FileViewMetadataProviderTest {
	
	@Mock
	FileViewManager tableVeiwManager;

	FileViewMetadataProvider provider;
	
	FileView view;
	UserInfo user;
	
	@Before
	public void before(){
		MockitoAnnotations.initMocks(this);
		provider = new FileViewMetadataProvider();
		ReflectionTestUtils.setField(provider, "tableVeiwManager", tableVeiwManager);
		
		view = new FileView();
		view.setColumnIds(Lists.newArrayList("111","222"));
		view.setContainerScope(Lists.newArrayList("syn444"));
		view.setId("syn888");
		
		user = new UserInfo(false, 55L);
	}
	
	@Test
	public void testValidateEntityCreate(){
		provider.entityCreated(user, view);
		verify(tableVeiwManager).setViewSchemaAndScope(user, view.getColumnIds(), view.getContainerScope(), view.getId());
	}

	@Test
	public void testValidateEntityUpdate(){
		provider.entityUpdated(user, view);
		verify(tableVeiwManager).setViewSchemaAndScope(user, view.getColumnIds(), view.getContainerScope(), view.getId());
	}
	
}
