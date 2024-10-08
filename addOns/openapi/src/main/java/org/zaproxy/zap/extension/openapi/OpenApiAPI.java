/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2017 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.zap.extension.openapi;

import java.io.File;
import java.util.List;
import net.sf.json.JSONObject;
import org.apache.commons.httpclient.URI;
import org.apache.commons.httpclient.URIException;
import org.parosproxy.paros.control.Control;
import org.zaproxy.zap.extension.api.ApiAction;
import org.zaproxy.zap.extension.api.ApiException;
import org.zaproxy.zap.extension.api.ApiException.Type;
import org.zaproxy.zap.extension.api.ApiImplementor;
import org.zaproxy.zap.extension.api.ApiResponse;
import org.zaproxy.zap.extension.api.ApiResponseElement;
import org.zaproxy.zap.extension.api.ApiResponseList;
import org.zaproxy.zap.extension.openapi.OpenApiExceptions.EmptyDefinitionException;
import org.zaproxy.zap.extension.openapi.OpenApiExceptions.InvalidDefinitionException;
import org.zaproxy.zap.extension.openapi.OpenApiExceptions.InvalidUrlException;
import org.zaproxy.zap.extension.users.ExtensionUserManagement;
import org.zaproxy.zap.model.Context;
import org.zaproxy.zap.users.User;
import org.zaproxy.zap.utils.ApiUtils;

public class OpenApiAPI extends ApiImplementor {

    private static final String PREFIX = "openapi";
    private static final String ACTION_IMPORT_FILE = "importFile";
    private static final String ACTION_IMPORT_URL = "importUrl";
    private static final String PARAM_URL = "url";
    private static final String PARAM_FILE = "file";
    private static final String PARAM_TARGET = "target";
    private static final String PARAM_CONTEXT_ID = "contextId";
    private static final String PARAM_USER_ID = "userId";

    private static final String PARAM_HOST_OVERRIDE = "hostOverride";
    private ExtensionOpenApi extension = null;

    /** Provided only for API client generator usage. */
    public OpenApiAPI() {
        this(null);
    }

    public OpenApiAPI(ExtensionOpenApi ext) {
        extension = ext;
        this.addApiAction(
                new ApiAction(
                        ACTION_IMPORT_FILE,
                        new String[] {PARAM_FILE},
                        new String[] {PARAM_TARGET, PARAM_CONTEXT_ID, PARAM_USER_ID}));
        this.addApiAction(
                new ApiAction(
                        ACTION_IMPORT_URL,
                        new String[] {PARAM_URL},
                        new String[] {PARAM_HOST_OVERRIDE, PARAM_CONTEXT_ID, PARAM_USER_ID}));
    }

    @Override
    public String getPrefix() {
        return PREFIX;
    }

    @Override
    public ApiResponse handleApiAction(String name, JSONObject params) throws ApiException {
        if (ACTION_IMPORT_FILE.equals(name)) {
            File file = new File(params.getString(PARAM_FILE));
            if (!file.exists() || !file.canRead()) {
                throw new ApiException(ApiException.Type.DOES_NOT_EXIST, file.getAbsolutePath());
            }

            if (!file.isFile()) {
                throw new ApiException(ApiException.Type.ILLEGAL_PARAMETER, PARAM_FILE);
            }
            List<String> errors;
            String target = params.optString(PARAM_TARGET, "");
            int ctxId = getContextId(params);
            User user = getUser(ctxId, params);
            try {
                errors = extension.importOpenApiDefinition(file, target, false, ctxId, user);
            } catch (InvalidUrlException e) {
                throw new ApiException(ApiException.Type.ILLEGAL_PARAMETER, PARAM_TARGET);
            } catch (EmptyDefinitionException | InvalidDefinitionException e) {
                throw new ApiException(ApiException.Type.BAD_EXTERNAL_DATA, e.getMessage(), e);
            }

            if (errors == null) {
                // A null list indicates that an exception occurred while parsing the file...
                throw new ApiException(ApiException.Type.BAD_EXTERNAL_DATA, PARAM_FILE);
            }

            ApiResponseList result = new ApiResponseList(name);
            for (String error : errors) {
                result.addItem(new ApiResponseElement("warning", error));
            }

            return result;

        } else if (ACTION_IMPORT_URL.equals(name)) {

            try {
                String override = params.optString(PARAM_HOST_OVERRIDE, "");

                int ctxId = getContextId(params);
                User user = getUser(ctxId, params);
                List<String> errors =
                        extension.importOpenApiDefinition(
                                new URI(params.getString(PARAM_URL), false),
                                override,
                                false,
                                ctxId,
                                user);

                if (errors == null) {
                    throw new ApiException(
                            ApiException.Type.ILLEGAL_PARAMETER, "Failed to access the target.");
                }

                ApiResponseList result = new ApiResponseList(name);
                for (String error : errors) {
                    result.addItem(new ApiResponseElement("warning", error));
                }

                return result;
            } catch (URIException e) {
                throw new ApiException(ApiException.Type.ILLEGAL_PARAMETER, PARAM_URL);
            } catch (InvalidUrlException e) {
                throw new ApiException(ApiException.Type.ILLEGAL_PARAMETER, PARAM_HOST_OVERRIDE);
            } catch (EmptyDefinitionException | InvalidDefinitionException e) {
                throw new ApiException(ApiException.Type.BAD_EXTERNAL_DATA, e.getMessage(), e);
            }
        } else {
            throw new ApiException(ApiException.Type.BAD_ACTION);
        }
    }

    private int getContextId(JSONObject params) throws ApiException {
        if (params.containsKey(PARAM_CONTEXT_ID) && !params.getString(PARAM_CONTEXT_ID).isEmpty()) {
            return ApiUtils.getContextByParamId(params, PARAM_CONTEXT_ID).getId();
        }

        List<Context> contexts = extension.getModel().getSession().getContexts();
        if (!contexts.isEmpty()) {
            return contexts.get(0).getId();
        }
        return -1;
    }

    private static User getUser(int ctxId, JSONObject params) throws ApiException {
        if (!params.containsKey(PARAM_USER_ID) || params.getString(PARAM_USER_ID).isBlank()) {
            return null;
        }

        if (ctxId == -1) {
            throw new ApiException(Type.MISSING_PARAMETER, PARAM_CONTEXT_ID);
        }

        int userId = ApiUtils.getIntParam(params, PARAM_USER_ID);
        ExtensionUserManagement usersExtension =
                Control.getSingleton()
                        .getExtensionLoader()
                        .getExtension(ExtensionUserManagement.class);
        if (usersExtension == null) {
            throw new ApiException(Type.NO_IMPLEMENTOR, ExtensionUserManagement.NAME);
        }
        User user = usersExtension.getContextUserAuthManager(ctxId).getUserById(userId);
        if (user == null) {
            throw new ApiException(Type.USER_NOT_FOUND, PARAM_USER_ID);
        }
        return user;
    }
}
