/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.ml.action.upload_chunk;

import static org.opensearch.ml.utils.MLExceptionUtils.logException;

import org.apache.commons.lang3.StringUtils;
import org.opensearch.action.ActionRequest;
import org.opensearch.action.support.ActionFilters;
import org.opensearch.action.support.HandledTransportAction;
import org.opensearch.client.Client;
import org.opensearch.common.inject.Inject;
import org.opensearch.commons.authuser.User;
import org.opensearch.core.action.ActionListener;
import org.opensearch.ml.common.MLTaskState;
import org.opensearch.ml.common.transport.model_group.MLRegisterModelGroupInput;
import org.opensearch.ml.common.transport.upload_chunk.MLRegisterModelMetaAction;
import org.opensearch.ml.common.transport.upload_chunk.MLRegisterModelMetaInput;
import org.opensearch.ml.common.transport.upload_chunk.MLRegisterModelMetaRequest;
import org.opensearch.ml.common.transport.upload_chunk.MLRegisterModelMetaResponse;
import org.opensearch.ml.helper.ModelAccessControlHelper;
import org.opensearch.ml.model.MLModelGroupManager;
import org.opensearch.ml.model.MLModelManager;
import org.opensearch.ml.utils.RestActionUtils;
import org.opensearch.tasks.Task;
import org.opensearch.transport.TransportService;

import lombok.extern.log4j.Log4j2;

@Log4j2
public class TransportRegisterModelMetaAction extends HandledTransportAction<ActionRequest, MLRegisterModelMetaResponse> {

    TransportService transportService;
    ActionFilters actionFilters;
    MLModelManager mlModelManager;
    Client client;
    ModelAccessControlHelper modelAccessControlHelper;
    MLModelGroupManager mlModelGroupManager;

    @Inject
    public TransportRegisterModelMetaAction(
        TransportService transportService,
        ActionFilters actionFilters,
        MLModelManager mlModelManager,
        Client client,
        ModelAccessControlHelper modelAccessControlHelper,
        MLModelGroupManager mlModelGroupManager
    ) {
        super(MLRegisterModelMetaAction.NAME, transportService, actionFilters, MLRegisterModelMetaRequest::new);
        this.transportService = transportService;
        this.actionFilters = actionFilters;
        this.mlModelManager = mlModelManager;
        this.client = client;
        this.modelAccessControlHelper = modelAccessControlHelper;
        this.mlModelGroupManager = mlModelGroupManager;
    }

    @Override
    protected void doExecute(Task task, ActionRequest request, ActionListener<MLRegisterModelMetaResponse> listener) {
        MLRegisterModelMetaRequest registerModelMetaRequest = MLRegisterModelMetaRequest.fromActionRequest(request);
        MLRegisterModelMetaInput mlUploadInput = registerModelMetaRequest.getMlRegisterModelMetaInput();

        User user = RestActionUtils.getUserContext(client);

        modelAccessControlHelper.validateModelGroupAccess(user, mlUploadInput.getModelGroupId(), client, ActionListener.wrap(access -> {
            if (!access) {
                log.error("You don't have permissions to perform this operation on this model.");
                listener.onFailure(new IllegalArgumentException("You don't have permissions to perform this operation on this model."));
            } else {
                if (StringUtils.isEmpty(mlUploadInput.getModelGroupId())) {
                    MLRegisterModelGroupInput mlRegisterModelGroupInput = createRegisterModelGroupRequest(mlUploadInput);
                    mlModelGroupManager.createModelGroup(mlRegisterModelGroupInput, ActionListener.wrap(modelGroupId -> {
                        mlUploadInput.setModelGroupId(modelGroupId);
                        registerModelMeta(mlUploadInput, listener);
                    }, e -> {
                        logException("Failed to create Model Group", e, log);
                        listener.onFailure(e);
                    }));
                } else {
                    registerModelMeta(mlUploadInput, listener);
                }
            }
        }, e -> {
            logException("Failed to validate model access", e, log);
            listener.onFailure(e);
        }));
    }

    private MLRegisterModelGroupInput createRegisterModelGroupRequest(MLRegisterModelMetaInput mlUploadInput) {
        return MLRegisterModelGroupInput
            .builder()
            .name(mlUploadInput.getName())
            .description(mlUploadInput.getDescription())
            .backendRoles(mlUploadInput.getBackendRoles())
            .modelAccessMode(mlUploadInput.getAccessMode())
            .isAddAllBackendRoles(mlUploadInput.getIsAddAllBackendRoles())
            .build();
    }

    private void registerModelMeta(MLRegisterModelMetaInput mlUploadInput, ActionListener<MLRegisterModelMetaResponse> listener) {
        mlModelManager.registerModelMeta(mlUploadInput, ActionListener.wrap(modelId -> {
            listener.onResponse(new MLRegisterModelMetaResponse(modelId, MLTaskState.CREATED.name()));
        }, ex -> {
            log.error("Failed to init model index", ex);
            listener.onFailure(ex);
        }));
    }
}
