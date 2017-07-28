/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hadoop.yarn.server.timelineservice.collector;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.http.HttpServer2;
import org.apache.hadoop.net.NetUtils;
import org.apache.hadoop.security.SecurityUtil;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.exceptions.YarnRuntimeException;
import org.apache.hadoop.yarn.ipc.YarnRPC;
import org.apache.hadoop.yarn.server.api.CollectorNodemanagerProtocol;
import org.apache.hadoop.yarn.server.api.protocolrecords.GetTimelineCollectorContextRequest;
import org.apache.hadoop.yarn.server.api.protocolrecords.GetTimelineCollectorContextResponse;
import org.apache.hadoop.yarn.server.api.protocolrecords.ReportNewCollectorInfoRequest;
import org.apache.hadoop.yarn.server.timelineservice.security.TimelineV2DelegationTokenSecretManagerService;
import org.apache.hadoop.yarn.server.util.timeline.TimelineServerUtils;
import org.apache.hadoop.yarn.webapp.GenericExceptionHandler;
import org.apache.hadoop.yarn.webapp.YarnJacksonJaxbJsonProvider;
import org.apache.hadoop.yarn.webapp.util.WebAppUtils;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class on the NodeManager side that manages adding and removing collectors and
 * their lifecycle. Also instantiates the per-node collector webapp.
 */
@Private
@Unstable
public class NodeTimelineCollectorManager extends TimelineCollectorManager {
  private static final Logger LOG =
      LoggerFactory.getLogger(NodeTimelineCollectorManager.class);

  // REST server for this collector manager.
  private HttpServer2 timelineRestServer;

  private String timelineRestServerBindAddress;

  private volatile CollectorNodemanagerProtocol nmCollectorService;

  private TimelineV2DelegationTokenSecretManagerService tokenMgrService;

  private final boolean runningAsAuxService;

  static final String COLLECTOR_MANAGER_ATTR_KEY = "collector.manager";

  @VisibleForTesting
  protected NodeTimelineCollectorManager() {
    this(true);
  }

  protected NodeTimelineCollectorManager(boolean asAuxService) {
    super(NodeTimelineCollectorManager.class.getName());
    this.runningAsAuxService = asAuxService;
  }

  @Override
  protected void serviceInit(Configuration conf) throws Exception {
    tokenMgrService = new TimelineV2DelegationTokenSecretManagerService();
    addService(tokenMgrService);
    super.serviceInit(conf);
  }

  @Override
  protected void serviceStart() throws Exception {
    if (UserGroupInformation.isSecurityEnabled() && !runningAsAuxService) {
      // Do security login for cases where collector is running outside NM.
      try {
        doSecureLogin();
      } catch(IOException ie) {
        throw new YarnRuntimeException("Failed to login", ie);
      }
    }
    super.serviceStart();
    startWebApp();
  }

  private void doSecureLogin() throws IOException {
    Configuration conf = getConfig();
    InetSocketAddress addr = NetUtils.createSocketAddr(conf.getTrimmed(
        YarnConfiguration.TIMELINE_SERVICE_BIND_HOST,
            YarnConfiguration.DEFAULT_TIMELINE_SERVICE_BIND_HOST), 0,
                YarnConfiguration.TIMELINE_SERVICE_BIND_HOST);
    SecurityUtil.login(conf, YarnConfiguration.TIMELINE_SERVICE_KEYTAB,
        YarnConfiguration.TIMELINE_SERVICE_PRINCIPAL, addr.getHostName());
  }

  @Override
  protected void serviceStop() throws Exception {
    if (timelineRestServer != null) {
      timelineRestServer.stop();
    }
    super.serviceStop();
  }

  @Override
  protected void doPostPut(ApplicationId appId, TimelineCollector collector) {
    try {
      // Get context info from NM
      updateTimelineCollectorContext(appId, collector);
      // Report to NM if a new collector is added.
      reportNewCollectorToNM(appId);
    } catch (YarnException | IOException e) {
      // throw exception here as it cannot be used if failed communicate with NM
      LOG.error("Failed to communicate with NM Collector Service for " + appId);
      throw new YarnRuntimeException(e);
    }
  }

  /**
   * Launch the REST web server for this collector manager.
   */
  private void startWebApp() {
    Configuration conf = getConfig();
    String initializers = conf.get("hadoop.http.filter.initializers", "");
    Set<String> defaultInitializers = new LinkedHashSet<String>();
    TimelineServerUtils.addTimelineAuthFilter(
        initializers, defaultInitializers, tokenMgrService);
    TimelineServerUtils.setTimelineFilters(
        conf, initializers, defaultInitializers);
    String bindAddress = conf.get(YarnConfiguration.TIMELINE_SERVICE_BIND_HOST,
        YarnConfiguration.DEFAULT_TIMELINE_SERVICE_BIND_HOST) + ":0";
    try {
      HttpServer2.Builder builder = new HttpServer2.Builder()
          .setName("timeline")
          .setConf(conf)
          .addEndpoint(URI.create(
              (YarnConfiguration.useHttps(conf) ? "https://" : "http://") +
                  bindAddress));
      if (YarnConfiguration.useHttps(conf)) {
        builder = WebAppUtils.loadSslConfiguration(builder, conf);
      }
      timelineRestServer = builder.build();

      timelineRestServer.addJerseyResourcePackage(
          TimelineCollectorWebService.class.getPackage().getName() + ";"
              + GenericExceptionHandler.class.getPackage().getName() + ";"
              + YarnJacksonJaxbJsonProvider.class.getPackage().getName(),
          "/*");
      timelineRestServer.setAttribute(COLLECTOR_MANAGER_ATTR_KEY, this);
      timelineRestServer.start();
    } catch (Exception e) {
      String msg = "The per-node collector webapp failed to start.";
      LOG.error(msg, e);
      throw new YarnRuntimeException(msg, e);
    }
    //TODO: We need to think of the case of multiple interfaces
    this.timelineRestServerBindAddress = WebAppUtils.getResolvedAddress(
        timelineRestServer.getConnectorAddress(0));
    LOG.info("Instantiated the per-node collector webapp at " +
        timelineRestServerBindAddress);
  }

  private void reportNewCollectorToNM(ApplicationId appId)
      throws YarnException, IOException {
    ReportNewCollectorInfoRequest request =
        ReportNewCollectorInfoRequest.newInstance(appId,
            this.timelineRestServerBindAddress);
    LOG.info("Report a new collector for application: " + appId +
        " to the NM Collector Service.");
    getNMCollectorService().reportNewCollectorInfo(request);
  }

  private void updateTimelineCollectorContext(
      ApplicationId appId, TimelineCollector collector)
      throws YarnException, IOException {
    GetTimelineCollectorContextRequest request =
        GetTimelineCollectorContextRequest.newInstance(appId);
    LOG.info("Get timeline collector context for " + appId);
    GetTimelineCollectorContextResponse response =
        getNMCollectorService().getTimelineCollectorContext(request);
    String userId = response.getUserId();
    if (userId != null && !userId.isEmpty()) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Setting the user in the context: " + userId);
      }
      collector.getTimelineEntityContext().setUserId(userId);
    }
    String flowName = response.getFlowName();
    if (flowName != null && !flowName.isEmpty()) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Setting the flow name: " + flowName);
      }
      collector.getTimelineEntityContext().setFlowName(flowName);
    }
    String flowVersion = response.getFlowVersion();
    if (flowVersion != null && !flowVersion.isEmpty()) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Setting the flow version: " + flowVersion);
      }
      collector.getTimelineEntityContext().setFlowVersion(flowVersion);
    }
    long flowRunId = response.getFlowRunId();
    if (flowRunId != 0L) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("Setting the flow run id: " + flowRunId);
      }
      collector.getTimelineEntityContext().setFlowRunId(flowRunId);
    }
  }

  @VisibleForTesting
  protected CollectorNodemanagerProtocol getNMCollectorService() {
    if (nmCollectorService == null) {
      synchronized (this) {
        if (nmCollectorService == null) {
          Configuration conf = getConfig();
          InetSocketAddress nmCollectorServiceAddress = conf.getSocketAddr(
              YarnConfiguration.NM_BIND_HOST,
              YarnConfiguration.NM_COLLECTOR_SERVICE_ADDRESS,
              YarnConfiguration.DEFAULT_NM_COLLECTOR_SERVICE_ADDRESS,
              YarnConfiguration.DEFAULT_NM_COLLECTOR_SERVICE_PORT);
          LOG.info("nmCollectorServiceAddress: " + nmCollectorServiceAddress);
          final YarnRPC rpc = YarnRPC.create(conf);

          // TODO Security settings.
          nmCollectorService = (CollectorNodemanagerProtocol) rpc.getProxy(
              CollectorNodemanagerProtocol.class,
              nmCollectorServiceAddress, conf);
        }
      }
    }
    return nmCollectorService;
  }

  @VisibleForTesting
  public String getRestServerBindAddress() {
    return timelineRestServerBindAddress;
  }
}