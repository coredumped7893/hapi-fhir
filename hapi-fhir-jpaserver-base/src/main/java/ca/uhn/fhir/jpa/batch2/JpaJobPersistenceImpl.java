/*-
 * #%L
 * HAPI FHIR JPA Server
 * %%
 * Copyright (C) 2014 - 2023 Smile CDR, Inc.
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */
package ca.uhn.fhir.jpa.batch2;

import ca.uhn.fhir.batch2.api.IJobPersistence;
import ca.uhn.fhir.batch2.api.JobOperationResultJson;
import ca.uhn.fhir.batch2.model.FetchJobInstancesRequest;
import ca.uhn.fhir.batch2.model.JobInstance;
import ca.uhn.fhir.batch2.model.StatusEnum;
import ca.uhn.fhir.batch2.model.WorkChunk;
import ca.uhn.fhir.batch2.model.WorkChunkCompletionEvent;
import ca.uhn.fhir.batch2.model.WorkChunkCreateEvent;
import ca.uhn.fhir.batch2.model.WorkChunkErrorEvent;
import ca.uhn.fhir.batch2.model.WorkChunkStatusEnum;
import ca.uhn.fhir.batch2.models.JobInstanceFetchRequest;
import ca.uhn.fhir.jpa.dao.data.IBatch2JobInstanceRepository;
import ca.uhn.fhir.jpa.dao.data.IBatch2WorkChunkRepository;
import ca.uhn.fhir.jpa.dao.tx.IHapiTransactionService;
import ca.uhn.fhir.jpa.entity.Batch2JobInstanceEntity;
import ca.uhn.fhir.jpa.entity.Batch2WorkChunkEntity;
import ca.uhn.fhir.jpa.util.JobInstanceUtil;
import ca.uhn.fhir.model.api.PagingIterator;
import ca.uhn.fhir.util.Logs;
import org.apache.commons.collections4.ListUtils;
import org.apache.commons.lang3.Validate;
import org.slf4j.Logger;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.persistence.EntityManager;
import javax.persistence.Query;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static ca.uhn.fhir.jpa.entity.Batch2WorkChunkEntity.ERROR_MSG_MAX_LENGTH;
import static org.apache.commons.lang3.StringUtils.isBlank;

public class JpaJobPersistenceImpl implements IJobPersistence {
	private static final Logger ourLog = Logs.getBatchTroubleshootingLog();
	public static final String CREATE_TIME = "myCreateTime";

	private final IBatch2JobInstanceRepository myJobInstanceRepository;
	private final IBatch2WorkChunkRepository myWorkChunkRepository;
	private final EntityManager myEntityManager;
	private final IHapiTransactionService myTransactionService;

	/**
	 * Constructor
	 */
	public JpaJobPersistenceImpl(IBatch2JobInstanceRepository theJobInstanceRepository, IBatch2WorkChunkRepository theWorkChunkRepository, IHapiTransactionService theTransactionService, EntityManager theEntityManager) {
		Validate.notNull(theJobInstanceRepository);
		Validate.notNull(theWorkChunkRepository);
		myJobInstanceRepository = theJobInstanceRepository;
		myWorkChunkRepository = theWorkChunkRepository;
		myTransactionService = theTransactionService;
		myEntityManager = theEntityManager;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public String onWorkChunkCreate(WorkChunkCreateEvent theBatchWorkChunk) {
		Batch2WorkChunkEntity entity = new Batch2WorkChunkEntity();
		entity.setId(UUID.randomUUID().toString());
		entity.setSequence(theBatchWorkChunk.sequence);
		entity.setJobDefinitionId(theBatchWorkChunk.jobDefinitionId);
		entity.setJobDefinitionVersion(theBatchWorkChunk.jobDefinitionVersion);
		entity.setTargetStepId(theBatchWorkChunk.targetStepId);
		entity.setInstanceId(theBatchWorkChunk.instanceId);
		entity.setSerializedData(theBatchWorkChunk.serializedData);
		entity.setCreateTime(new Date());
		entity.setStartTime(new Date());
		entity.setStatus(WorkChunkStatusEnum.QUEUED);
		myWorkChunkRepository.save(entity);
		return entity.getId();
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public Optional<WorkChunk> onWorkChunkDequeue(String theChunkId) {
		int rowsModified = myWorkChunkRepository.updateChunkStatusForStart(theChunkId, new Date(), WorkChunkStatusEnum.IN_PROGRESS, List.of(WorkChunkStatusEnum.QUEUED, WorkChunkStatusEnum.ERRORED, WorkChunkStatusEnum.IN_PROGRESS));
		if (rowsModified == 0) {
			ourLog.info("Attempting to start chunk {} but it was already started.", theChunkId);
			return Optional.empty();
		} else {
			Optional<Batch2WorkChunkEntity> chunk = myWorkChunkRepository.findById(theChunkId);
			return chunk.map(t -> toChunk(t, true));
		}
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRED)
	public String storeNewInstance(JobInstance theInstance) {
		Validate.isTrue(isBlank(theInstance.getInstanceId()));

		Batch2JobInstanceEntity entity = new Batch2JobInstanceEntity();
		entity.setId(UUID.randomUUID().toString());
		entity.setDefinitionId(theInstance.getJobDefinitionId());
		entity.setDefinitionVersion(theInstance.getJobDefinitionVersion());
		entity.setStatus(theInstance.getStatus());
		entity.setParams(theInstance.getParameters());
		entity.setCurrentGatedStepId(theInstance.getCurrentGatedStepId());
		entity.setFastTracking(theInstance.isFastTracking());
		entity.setCreateTime(new Date());
		entity.setStartTime(new Date());
		entity.setReport(theInstance.getReport());

		entity = myJobInstanceRepository.save(entity);
		return entity.getId();
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<JobInstance> fetchInstances(String theJobDefinitionId, Set<StatusEnum> theStatuses, Date theCutoff, Pageable thePageable) {
		return toInstanceList(myJobInstanceRepository.findInstancesByJobIdAndStatusAndExpiry(theJobDefinitionId, theStatuses, theCutoff, thePageable));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<JobInstance> fetchInstancesByJobDefinitionIdAndStatus(String theJobDefinitionId, Set<StatusEnum> theRequestedStatuses, int thePageSize, int thePageIndex) {
		PageRequest pageRequest = PageRequest.of(thePageIndex, thePageSize, Sort.Direction.ASC, CREATE_TIME);
		return toInstanceList(myJobInstanceRepository.fetchInstancesByJobDefinitionIdAndStatus(theJobDefinitionId, theRequestedStatuses, pageRequest));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<JobInstance> fetchInstancesByJobDefinitionId(String theJobDefinitionId, int thePageSize, int thePageIndex) {
		PageRequest pageRequest = PageRequest.of(thePageIndex, thePageSize, Sort.Direction.ASC, CREATE_TIME);
		return toInstanceList(myJobInstanceRepository.findInstancesByJobDefinitionId(theJobDefinitionId, pageRequest));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Page<JobInstance> fetchJobInstances(JobInstanceFetchRequest theRequest) {
		PageRequest pageRequest = PageRequest.of(
			theRequest.getPageStart(),
			theRequest.getBatchSize(),
			theRequest.getSort()
		);

		Page<Batch2JobInstanceEntity> pageOfEntities = myJobInstanceRepository.findAll(pageRequest);

		return pageOfEntities.map(this::toInstance);
	}

	private List<JobInstance> toInstanceList(List<Batch2JobInstanceEntity> theInstancesByJobDefinitionId) {
		return theInstancesByJobDefinitionId.stream().map(this::toInstance).collect(Collectors.toList());
	}

	@Override
	@Nonnull
	public Optional<JobInstance> fetchInstance(String theInstanceId) {
		return myTransactionService
			.withSystemRequest()
			.execute(() -> myJobInstanceRepository.findById(theInstanceId).map(this::toInstance));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<JobInstance> fetchInstances(FetchJobInstancesRequest theRequest, int thePage, int theBatchSize) {
		String definitionId = theRequest.getJobDefinition();
		String params = theRequest.getParameters();
		Set<StatusEnum> statuses = theRequest.getStatuses();

		Pageable pageable = PageRequest.of(thePage, theBatchSize);

		List<Batch2JobInstanceEntity> instanceEntities;

		if (statuses != null && !statuses.isEmpty()) {
			instanceEntities = myJobInstanceRepository.findInstancesByJobIdParamsAndStatus(
				definitionId,
				params,
				statuses,
				pageable
			);
		} else {
			instanceEntities = myJobInstanceRepository.findInstancesByJobIdAndParams(
				definitionId,
				params,
				pageable
			);
		}
		return toInstanceList(instanceEntities);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<JobInstance> fetchInstances(int thePageSize, int thePageIndex) {
		// default sort is myCreateTime Asc
		PageRequest pageRequest = PageRequest.of(thePageIndex, thePageSize, Sort.Direction.ASC, CREATE_TIME);
		return myJobInstanceRepository.findAll(pageRequest).stream().map(this::toInstance).collect(Collectors.toList());
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public List<JobInstance> fetchRecentInstances(int thePageSize, int thePageIndex) {
		PageRequest pageRequest = PageRequest.of(thePageIndex, thePageSize, Sort.Direction.DESC, CREATE_TIME);
		return myJobInstanceRepository.findAll(pageRequest).stream().map(this::toInstance).collect(Collectors.toList());
	}

	private WorkChunk toChunk(Batch2WorkChunkEntity theEntity, boolean theIncludeData) {
		return JobInstanceUtil.fromEntityToWorkChunk(theEntity, theIncludeData);
	}

	private JobInstance toInstance(Batch2JobInstanceEntity theEntity) {
		return JobInstanceUtil.fromEntityToInstance(theEntity);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public WorkChunkStatusEnum onWorkChunkError(WorkChunkErrorEvent theParameters) {
		String chunkId = theParameters.getChunkId();
		String errorMessage = truncateErrorMessage(theParameters.getErrorMsg());
		int changeCount = myWorkChunkRepository.updateChunkStatusAndIncrementErrorCountForEndError(chunkId, new Date(), errorMessage, WorkChunkStatusEnum.ERRORED);
		Validate.isTrue(changeCount>0, "changed chunk matching %s", chunkId);

		Query query = myEntityManager.createQuery(
			"update Batch2WorkChunkEntity " +
				"set myStatus = :failed " +
				",myErrorMessage = CONCAT('Too many errors: ',  myErrorCount, '. Last error msg was ', myErrorMessage) " +
				"where myId = :chunkId and myErrorCount > :maxCount");
		query.setParameter("chunkId", chunkId);
		query.setParameter("failed", WorkChunkStatusEnum.FAILED);
		query.setParameter("maxCount", theParameters.getMaxRetries());
		int failChangeCount = query.executeUpdate();

		if (failChangeCount > 0) {
			return WorkChunkStatusEnum.FAILED;
		} else {
			return WorkChunkStatusEnum.ERRORED;
		}
	}

	@Override
	@Transactional
	public void onWorkChunkFailed(String theChunkId, String theErrorMessage) {
		ourLog.info("Marking chunk {} as failed with message: {}", theChunkId, theErrorMessage);
		String errorMessage = truncateErrorMessage(theErrorMessage);
		myWorkChunkRepository.updateChunkStatusAndIncrementErrorCountForEndError(theChunkId, new Date(), errorMessage, WorkChunkStatusEnum.FAILED);
	}

	@Override
	@Transactional
	public void onWorkChunkCompletion(WorkChunkCompletionEvent theEvent) {
		myWorkChunkRepository.updateChunkStatusAndClearDataForEndSuccess(theEvent.getChunkId(), new Date(), theEvent.getRecordsProcessed(), theEvent.getRecoveredErrorCount(), WorkChunkStatusEnum.COMPLETED);
	}

	@Nullable
	private static String truncateErrorMessage(String theErrorMessage) {
		String errorMessage;
		if (theErrorMessage != null && theErrorMessage.length() > ERROR_MSG_MAX_LENGTH) {
			ourLog.warn("Truncating error message that is too long to store in database: {}", theErrorMessage);
			errorMessage = theErrorMessage.substring(0, ERROR_MSG_MAX_LENGTH);
		} else {
			errorMessage = theErrorMessage;
		}
		return errorMessage;
	}

	@Override
	@Transactional
	public void markWorkChunksWithStatusAndWipeData(String theInstanceId, List<String> theChunkIds, WorkChunkStatusEnum theStatus, String theErrorMessage) {
		assert TransactionSynchronizationManager.isActualTransactionActive();

		ourLog.debug("Marking all chunks for instance {} to status {}", theInstanceId, theStatus);
		String errorMessage = truncateErrorMessage(theErrorMessage);
		List<List<String>> listOfListOfIds = ListUtils.partition(theChunkIds, 100);
		for (List<String> idList : listOfListOfIds) {
			myWorkChunkRepository.updateAllChunksForInstanceStatusClearDataAndSetError(idList, new Date(), theStatus, errorMessage);
		}
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean canAdvanceInstanceToNextStep(String theInstanceId, String theCurrentStepId) {
		Optional<Batch2JobInstanceEntity> instance = myJobInstanceRepository.findById(theInstanceId);
		if (!instance.isPresent()) {
			return false;
		}
		if (instance.get().getStatus().isEnded()) {
			return false;
		}
		List<WorkChunkStatusEnum> statusesForStep = myWorkChunkRepository.getDistinctStatusesForStep(theInstanceId, theCurrentStepId);
		ourLog.debug("Checking whether gated job can advanced to next step. [instanceId={}, currentStepId={}, statusesForStep={}]", theInstanceId, theCurrentStepId, statusesForStep);
		return statusesForStep.stream().noneMatch(WorkChunkStatusEnum::isIncomplete) && statusesForStep.stream().anyMatch(status -> status == WorkChunkStatusEnum.COMPLETED);
	}

	/**
	 * Note: Not @Transactional because {@link #fetchChunks(String, boolean, int, int, Consumer)} starts a transaction
	 */
	@Override
	public List<WorkChunk> fetchWorkChunksWithoutData(String theInstanceId, int thePageSize, int thePageIndex) {
		ArrayList<WorkChunk> chunks = new ArrayList<>();
		fetchChunks(theInstanceId, false, thePageSize, thePageIndex, chunks::add);
		return chunks;
	}

	private void fetchChunks(String theInstanceId, boolean theIncludeData, int thePageSize, int thePageIndex, Consumer<WorkChunk> theConsumer) {
		myTransactionService
			.withSystemRequest()
			.withPropagation(Propagation.REQUIRES_NEW)
			.execute(() -> {
				List<Batch2WorkChunkEntity> chunks = myWorkChunkRepository.fetchChunks(PageRequest.of(thePageIndex, thePageSize), theInstanceId);
				for (Batch2WorkChunkEntity chunk : chunks) {
					theConsumer.accept(toChunk(chunk, theIncludeData));
				}
			});
	}

	@Override
	public List<String> fetchAllChunkIdsForStepWithStatus(String theInstanceId, String theStepId, WorkChunkStatusEnum theStatusEnum) {
		return myTransactionService
			.withSystemRequest()
			.withPropagation(Propagation.REQUIRES_NEW)
			.execute(() -> myWorkChunkRepository.fetchAllChunkIdsForStepWithStatus(theInstanceId, theStepId, theStatusEnum));
	}

	@Override
	public void updateInstanceUpdateTime(String theInstanceId) {
		myJobInstanceRepository.updateInstanceUpdateTime(theInstanceId, new Date());
	}


	/**
	 * Note: Not @Transactional because the transaction happens in a lambda that's called outside of this method's scope
	 */
	@Override
	public Iterator<WorkChunk> fetchAllWorkChunksIterator(String theInstanceId, boolean theWithData) {
		return new PagingIterator<>((thePageIndex, theBatchSize, theConsumer) -> fetchChunks(theInstanceId, theWithData, theBatchSize, thePageIndex, theConsumer));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Stream<WorkChunk> fetchAllWorkChunksForStepStream(String theInstanceId, String theStepId) {
		return myWorkChunkRepository.fetchChunksForStep(theInstanceId, theStepId).map(entity -> toChunk(entity, true));
	}

	/**
	 * Update the stored instance
	 *
	 * @param theInstance The instance - Must contain an ID
	 * @return true if the status changed
	 */
	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean updateInstance(JobInstance theInstance) {
		// Separate updating the status so we have atomic information about whether the status is changing
		int recordsChangedByStatusUpdate = myJobInstanceRepository.updateInstanceStatus(theInstance.getInstanceId(), theInstance.getStatus());

		Optional<Batch2JobInstanceEntity> instanceOpt = myJobInstanceRepository.findById(theInstance.getInstanceId());
		Batch2JobInstanceEntity instanceEntity = instanceOpt.orElseThrow(() -> new IllegalArgumentException("Unknown instance ID: " + theInstance.getInstanceId()));

		instanceEntity.setStartTime(theInstance.getStartTime());
		instanceEntity.setEndTime(theInstance.getEndTime());
		instanceEntity.setStatus(theInstance.getStatus());
		instanceEntity.setCancelled(theInstance.isCancelled());
		instanceEntity.setFastTracking(theInstance.isFastTracking());
		instanceEntity.setCombinedRecordsProcessed(theInstance.getCombinedRecordsProcessed());
		instanceEntity.setCombinedRecordsProcessedPerSecond(theInstance.getCombinedRecordsProcessedPerSecond());
		instanceEntity.setTotalElapsedMillis(theInstance.getTotalElapsedMillis());
		instanceEntity.setWorkChunksPurged(theInstance.isWorkChunksPurged());
		instanceEntity.setProgress(theInstance.getProgress());
		instanceEntity.setErrorMessage(theInstance.getErrorMessage());
		instanceEntity.setErrorCount(theInstance.getErrorCount());
		instanceEntity.setEstimatedTimeRemaining(theInstance.getEstimatedTimeRemaining());
		instanceEntity.setCurrentGatedStepId(theInstance.getCurrentGatedStepId());
		instanceEntity.setReport(theInstance.getReport());

		myJobInstanceRepository.save(instanceEntity);

		return recordsChangedByStatusUpdate > 0;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void deleteInstanceAndChunks(String theInstanceId) {
		ourLog.info("Deleting instance and chunks: {}", theInstanceId);
		myWorkChunkRepository.deleteAllForInstance(theInstanceId);
		myJobInstanceRepository.deleteById(theInstanceId);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void deleteChunksAndMarkInstanceAsChunksPurged(String theInstanceId) {
		ourLog.info("Deleting all chunks for instance ID: {}", theInstanceId);
		myJobInstanceRepository.updateWorkChunksPurgedTrue(theInstanceId);
		myWorkChunkRepository.deleteAllForInstance(theInstanceId);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean markInstanceAsCompleted(String theInstanceId) {
		int recordsChanged = myJobInstanceRepository.updateInstanceStatus(theInstanceId, StatusEnum.COMPLETED);
		return recordsChanged > 0;
	}

	@Override
	public boolean markInstanceAsStatus(String theInstance, StatusEnum theStatusEnum) {
		int recordsChanged =	myTransactionService
			.withSystemRequest()
			.execute(()->myJobInstanceRepository.updateInstanceStatus(theInstance, theStatusEnum));
		return recordsChanged > 0;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public JobOperationResultJson cancelInstance(String theInstanceId) {
		int recordsChanged = myJobInstanceRepository.updateInstanceCancelled(theInstanceId, true);
		String operationString = "Cancel job instance " + theInstanceId;

		if (recordsChanged > 0) {
			return JobOperationResultJson.newSuccess(operationString, "Job instance <" + theInstanceId + "> successfully cancelled.");
		} else {
			Optional<JobInstance> instance = fetchInstance(theInstanceId);
			if (instance.isPresent()) {
				return JobOperationResultJson.newFailure(operationString, "Job instance <" + theInstanceId + "> was already cancelled.  Nothing to do.");
			} else {
				return JobOperationResultJson.newFailure(operationString, "Job instance <" + theInstanceId + "> not found.");
			}
		}
	}


	@Override
	public void processCancelRequests() {
		myTransactionService
			.withSystemRequest()
			.execute(()->{
				Query query = myEntityManager.createQuery(
					"UPDATE Batch2JobInstanceEntity b " +
						"set myStatus = ca.uhn.fhir.batch2.model.StatusEnum.CANCELLED " +
						"where myCancelled = true " +
						"AND myStatus IN (:states)");
				query.setParameter("states", StatusEnum.CANCELLED.getPriorStates());
				query.executeUpdate();
			});
	}

}
