/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership.  The ASF
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.hadoop.yarn.server.federation.policies;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.exceptions.YarnException;
import org.apache.hadoop.yarn.server.federation.policies.amrmproxy.FederationAMRMProxyPolicy;
import org.apache.hadoop.yarn.server.federation.policies.exceptions.FederationPolicyInitializationException;
import org.apache.hadoop.yarn.server.federation.policies.manager.FederationPolicyManager;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterId;
import org.apache.hadoop.yarn.server.federation.store.records.SubClusterPolicyConfiguration;
import org.apache.hadoop.yarn.server.federation.utils.FederationStateStoreFacade;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utility class for Federation policy.
 */
@Private
public final class FederationPolicyUtils {
  private static final Logger LOG =
      LoggerFactory.getLogger(FederationPolicyUtils.class);

  /** Disable constructor. */
  private FederationPolicyUtils() {
  }

  /**
   * A utilize method to instantiate a policy manager class given the type
   * (class name) from {@link SubClusterPolicyConfiguration}.
   *
   * @param newType class name of the policy manager to create
   * @return Policy manager
   * @throws FederationPolicyInitializationException if fails
   */
  public static FederationPolicyManager instantiatePolicyManager(String newType)
      throws FederationPolicyInitializationException {
    FederationPolicyManager federationPolicyManager = null;
    try {
      // create policy instance and set queue
      Class<?> c = Class.forName(newType);
      federationPolicyManager = (FederationPolicyManager) c.newInstance();
    } catch (ClassNotFoundException e) {
      throw new FederationPolicyInitializationException(e);
    } catch (InstantiationException e) {
      throw new FederationPolicyInitializationException(e);
    } catch (IllegalAccessException e) {
      throw new FederationPolicyInitializationException(e);
    }
    return federationPolicyManager;
  }

  /**
   * Get Federation policy configuration from state store, using default queue
   * and configuration as fallback.
   *
   * @param queue the queue of the application
   * @param conf the Yarn configuration
   * @param federationFacade state store facade
   * @return SubClusterPolicyConfiguration recreated
   */
  public static SubClusterPolicyConfiguration loadPolicyConfiguration(
      String queue, Configuration conf,
      FederationStateStoreFacade federationFacade) {

    // The facade might cache this request, based on its parameterization
    SubClusterPolicyConfiguration configuration = null;
    if (queue != null) {
      try {
        configuration = federationFacade.getPolicyConfiguration(queue);
      } catch (YarnException e) {
        LOG.warn("Failed to get policy from FederationFacade with queue "
            + queue + ": " + e.getMessage());
      }
    }

    // If there is no policy configured for this queue, fallback to the baseline
    // policy that is configured either in the store or via XML config
    if (configuration == null) {
      LOG.info("No policy configured for queue {} in StateStore,"
          + " fallback to default queue", queue);
      queue = YarnConfiguration.DEFAULT_FEDERATION_POLICY_KEY;
      try {
        configuration = federationFacade.getPolicyConfiguration(queue);
      } catch (YarnException e) {
        LOG.warn("No fallback behavior defined in store, defaulting to XML "
            + "configuration fallback behavior.");
      }
    }

    // or from XML conf otherwise.
    if (configuration == null) {
      LOG.info("No policy configured for default queue {} in StateStore,"
          + " fallback to local config", queue);

      String defaultFederationPolicyManager =
          conf.get(YarnConfiguration.FEDERATION_POLICY_MANAGER,
              YarnConfiguration.DEFAULT_FEDERATION_POLICY_MANAGER);
      String defaultPolicyParamString =
          conf.get(YarnConfiguration.FEDERATION_POLICY_MANAGER_PARAMS,
              YarnConfiguration.DEFAULT_FEDERATION_POLICY_MANAGER_PARAMS);
      ByteBuffer defaultPolicyParam = ByteBuffer
          .wrap(defaultPolicyParamString.getBytes(StandardCharsets.UTF_8));

      configuration = SubClusterPolicyConfiguration.newInstance(queue,
          defaultFederationPolicyManager, defaultPolicyParam);
    }
    return configuration;
  }

  /**
   * Get AMRMProxy policy from state store, using default queue and
   * configuration as fallback.
   *
   * @param queue the queue of the application
   * @param oldPolicy the previous policy instance (can be null)
   * @param conf the Yarn configuration
   * @param federationFacade state store facade
   * @param homeSubClusterId home sub-cluster id
   * @return FederationAMRMProxyPolicy recreated
   * @throws FederationPolicyInitializationException if fails
   */
  public static FederationAMRMProxyPolicy loadAMRMPolicy(String queue,
      FederationAMRMProxyPolicy oldPolicy, Configuration conf,
      FederationStateStoreFacade federationFacade,
      SubClusterId homeSubClusterId)
      throws FederationPolicyInitializationException {

    // Local policy and its configuration
    SubClusterPolicyConfiguration configuration =
        loadPolicyConfiguration(queue, conf, federationFacade);

    // Instantiate the policyManager and get policy
    FederationPolicyInitializationContext context =
        new FederationPolicyInitializationContext(configuration,
            federationFacade.getSubClusterResolver(), federationFacade,
            homeSubClusterId);

    LOG.info("Creating policy manager of type: " + configuration.getType());
    FederationPolicyManager federationPolicyManager =
        instantiatePolicyManager(configuration.getType());
    // set queue, reinit policy if required (implementation lazily check
    // content of conf), and cache it
    federationPolicyManager.setQueue(configuration.getQueue());
    return federationPolicyManager.getAMRMPolicy(context, oldPolicy);
  }

}