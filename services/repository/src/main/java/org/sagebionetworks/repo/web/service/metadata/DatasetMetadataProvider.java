package org.sagebionetworks.repo.web.service.metadata;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.sagebionetworks.repo.manager.table.TableViewManager;
import org.sagebionetworks.repo.model.DatastoreException;
import org.sagebionetworks.repo.model.EntityHeader;
import org.sagebionetworks.repo.model.FileEntity;
import org.sagebionetworks.repo.model.InvalidModelException;
import org.sagebionetworks.repo.model.NodeDAO;
import org.sagebionetworks.repo.model.UnauthorizedException;
import org.sagebionetworks.repo.model.UserInfo;
import org.sagebionetworks.repo.model.jdo.KeyFactory;
import org.sagebionetworks.repo.model.table.Dataset;
import org.sagebionetworks.repo.model.table.DatasetItem;
import org.sagebionetworks.repo.model.table.ViewEntityType;
import org.sagebionetworks.repo.model.table.ViewScope;
import org.sagebionetworks.repo.web.NotFoundException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DatasetMetadataProvider extends ViewMetadataProvider<Dataset> implements EntityValidator<Dataset> {

	private NodeDAO nodeDao;

	@Autowired
	public DatasetMetadataProvider(TableViewManager viewManager, NodeDAO nodeDao) {
		super(viewManager);
		this.nodeDao = nodeDao;
	}

	@Override
	public void validateEntity(Dataset entity, EntityEvent event)
			throws InvalidModelException, NotFoundException, DatastoreException, UnauthorizedException {
		if (entity.getItems() != null) {
			
			Set<Long> uniqueIds = new HashSet<>(entity.getItems().size());
			for(DatasetItem item: entity.getItems()) {
				if(item.getEntityId() == null) {
					throw new IllegalArgumentException("Each dataset item must have a non-null entity ID.");
				}
				if(item.getVersionNumber() == null) {
					throw new IllegalArgumentException("Each dataset item must have a non-null version number");
				}
				if(!uniqueIds.add(KeyFactory.stringToKey(item.getEntityId()))) {
					throw new IllegalArgumentException("Each dataset item must have a unique entity ID.  Duplicate: "+item.getEntityId());
				}
			}

			
			// Only allow files
			List<EntityHeader> headers = nodeDao.getEntityHeader(entity.getItems().stream()
					.map(i -> KeyFactory.stringToKey(i.getEntityId())).collect(Collectors.toSet()));
			Optional<EntityHeader> firstNonFile = headers.stream()
					.filter(h -> !FileEntity.class.getName().equals(h.getType())).findFirst();
			if (firstNonFile.isPresent()) {
				throw new IllegalArgumentException(
						String.format("Currently, only files can be included in a dataset. %s is '%s'",
								firstNonFile.get().getId(), firstNonFile.get().getType()));
			}
		}
	}

	@Override
	public ViewScope createViewScope(UserInfo userInfo, Dataset dataset) {
		ViewScope scope = new ViewScope();
		scope.setViewEntityType(ViewEntityType.dataset);
		if (dataset.getItems() != null) {
			scope.setScope(dataset.getItems().stream().map(i -> i.getEntityId()).collect(Collectors.toList()));
		}
		scope.setViewTypeMask(0L);
		return scope;
	}

}
