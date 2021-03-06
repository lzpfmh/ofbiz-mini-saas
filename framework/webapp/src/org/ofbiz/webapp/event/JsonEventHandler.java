/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 *******************************************************************************/
package org.ofbiz.webapp.event;

import org.ofbiz.base.lang.JSON;
import org.ofbiz.base.util.Debug;
import org.ofbiz.base.util.UtilGenerics;
import org.ofbiz.base.util.UtilHttp;
import org.ofbiz.base.util.UtilMisc;
import org.ofbiz.entity.Delegator;
import org.ofbiz.entity.DelegatorFactory;
import org.ofbiz.entity.GenericEntityException;
import org.ofbiz.entity.GenericValue;
import org.ofbiz.entity.condition.EntityCondition;
import org.ofbiz.entity.util.EntityFindOptions;
import org.ofbiz.entity.util.EntityUtil;
import org.ofbiz.entity.util.EntityUtilProperties;
import org.ofbiz.service.*;
import org.ofbiz.webapp.EnterpriseModulesCache;
import org.ofbiz.webapp.control.ConfigXMLReader.Event;
import org.ofbiz.webapp.control.ConfigXMLReader.RequestMap;
import org.ofbiz.webapp.control.RequestHandler;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.Writer;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JsonEventHandler - JSON Event Handler implementation
 * Begin Project Name Specific: Developer – Date – Description
 */
public class JsonEventHandler implements EventHandler {

    public static final String module = JsonEventHandler.class.getName();

    /**
     * @see EventHandler#init(ServletContext)
     */
    public void init(ServletContext context) throws EventHandlerException {
    }

    /**
     * @see
     */
    public String invoke(Event event, RequestMap requestMap, HttpServletRequest request, HttpServletResponse response) throws EventHandlerException {
        LocalDispatcher dispatcher = (LocalDispatcher) request.getAttribute("dispatcher");
        String serviceName = RequestHandler.getOverrideViewUri(request.getPathInfo());
        // 检查登录
        String ticket = this.checkLogin(request, response);
        if (ticket == null) {
            response.setStatus(401);
            throw new EventHandlerException("Authentication Failed");
        }

        // 将Request中的参数组成Map
        Map<String, Object> parameters = UtilGenerics.cast(UtilHttp.getParameterMap(request));

        // TenantId设进去
        String tenantId = TicketUtil.getTenantIdFromRequest(request);
        dispatcher.getDelegator().setTenantId(tenantId);
        // 设备企业模块支持
        String enterpriseModules = EnterpriseModulesCache.getModulesByTenant(tenantId);
        if (enterpriseModules == null) {
            Delegator delegator = DelegatorFactory.getDelegator("default");
            try {
                // 只查询一条
                EntityFindOptions findOptions = new EntityFindOptions();
                findOptions.setMaxRows(1);
                List<GenericValue> enterpriseList = delegator.findList("Enterprise", EntityCondition.makeCondition(UtilMisc.toMap("domainName", tenantId)), UtilMisc.toSet("configs"), null, findOptions, false);
                if (enterpriseList == null || enterpriseList.size() != 1) {
                    throw new GenericEntityException("Enterprise[" + tenantId + "] is not found");
                }
                enterpriseModules = enterpriseList.get(0).get("configs").toString();
                EnterpriseModulesCache.putModules(tenantId, enterpriseModules);
                dispatcher.getDelegator().setEnterpriseModules(enterpriseModules);
            } catch (GenericEntityException e) {
                e.printStackTrace();
                throw new EventHandlerException("Enterprise[" + tenantId + "] is not found");
            }
        } else {
            dispatcher.getDelegator().setEnterpriseModules(enterpriseModules);
        }

        Debug.log(this.module + ",service:" + serviceName + ",path:" + request.getPathInfo() + ",parameters size:" + parameters.size());
        if (serviceName == null) {
            try {
                Writer writer = response.getWriter();
                StringBuilder sb = new StringBuilder();
                sb.append("{success:false,msg:\"service is not found.\"}");
                writer.write(sb.toString());
                writer.flush();
                return null;
            } catch (Exception e) {
                e.printStackTrace();
                response.setStatus(500);
                sendError(response, "ServiceName[" + serviceName + "] is not found", null);
                throw new EventHandlerException("ServiceName[" + serviceName + "] is not found");
            }
        }

        // not a wsdl request; invoke the service
        try {
            Writer writer = response.getWriter();
            StringBuilder sb = new StringBuilder();
            ModelService model = dispatcher.getDispatchContext().getModelService(serviceName);

            if (model == null) {
                sendError(response, "Problem processing the service", serviceName);
                Debug.logError("Could not find Service [" + serviceName + "].", module);
                return null;
            }

            if (!model.export) {
                sendError(response, "Problem processing the service", serviceName);
                Debug.logError("Trying to call Service [" + serviceName + "] that is not exported.", module);
                return null;
            }

            if (!this.checkAuth(model, ticket, dispatcher.getDelegator())) {
                response.setStatus(403);
                sendError(response, "Problem processing the service", serviceName);
                Debug.logError("No auth [" + serviceName + "]", module);
                return null;
            }

            // service模块使用,代替了框架的security
            parameters.put("ticket", ticket);
            // 必须选回一个result
            Map<String, Object> serviceResults = dispatcher.runSync(serviceName, parameters);
            Map<String, Object> resp = model.makeValid(serviceResults, ModelService.OUT_PARAM, false, null);

            String result = JSON.from(resp).toString();
            // 设置长度
            response.setCharacterEncoding("UTF8");
            response.setContentType("text/json");
            response.setContentLength(result.getBytes("utf-8").length);

            writer.write(result);
            writer.flush();
        } catch (Exception e) {
            e.printStackTrace();
            response.setStatus(500);
            sendError(response, e.getMessage(), serviceName);
            throw new EventHandlerException("Problem processing the servic");
        }
        return null;
    }

    private boolean checkAuth(ModelService service, String ticket, Delegator delegator) throws GenericEntityException {
        // -1 如果joinType是空，不做判断(暂时只有OR)
        if (service.permissionGroups.size() == 0) {
            return true;
        }
        // 判断Ticket的合法性
        if (EntityUtil.isMultiTenantEnabled() && !delegator.getTenantId().equals(TicketUtil.getTenantFromTicket(ticket))) {
            return false;
        }
        // 0 先查Ticket查权限编码
        Map<String, Object> fields = new HashMap<String, Object>();
        fields.put("ticket", ticket);
        GenericValue token = delegator.findOne("LoginToken", true, fields);
        if (token == null || token.get("permissionData") == null) {
            return false;
        }
        String permissionData = token.get("permissionData").toString();
        String[] pds = permissionData.split(",");
        // 1 判断是否包含该权限
        for (ModelPermGroup mpg : service.permissionGroups) {
            for (ModelPermission mp : mpg.permissions) {
                for (String pd : pds) {
                    if (mp.nameOrRole.contains(pd)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private String checkLogin(HttpServletRequest request, HttpServletResponse response) throws EventHandlerException {
        // 1 检查Token
        String ticket = request.getHeader("X-TICKET");
        GenericValue userLoginToken = null;

        if (ticket != null) {
            Delegator delegator = DelegatorFactory.getDelegator("default");
            if (EntityUtil.isMultiTenantEnabled()) {
                delegator.setTenantId(TicketUtil.getTenantIdFromRequest(request));
            }
            Map<String, Object> fields = new HashMap<String, Object>();
            fields.put("ticket", ticket);
            try {
                userLoginToken = delegator.findOne("LoginToken", fields, true);
            } catch (Exception e) {
                e.printStackTrace();
                return null;
            }
            if (userLoginToken == null || (EntityUtil.isMultiTenantEnabled() && !delegator.getTenantId().equals(TicketUtil.getTenantFromTicket(ticket)))) {
                response.setStatus(401);
                sendError(response, "it's not login", null);
                return null;
            }

            if (Calendar.getInstance().getTimeInMillis() > ((Timestamp) userLoginToken.get("expirationTime")).getTime()) {
                response.setStatus(401);
                sendError(response, "it's not login", null);
                return null;
            }

            return ticket;
        }

        return null;
    }

    private void sendError(HttpServletResponse res, String errorMessage, String serviceName) throws EventHandlerException {
        try {
            res.getWriter().write("{\"success\":false,\"message\":\"" + serviceName + ":" + errorMessage + "\"}");
            res.getWriter().flush();
        } catch (Exception e) {
            throw new EventHandlerException(e.getMessage(), e);
        }
    }
}
