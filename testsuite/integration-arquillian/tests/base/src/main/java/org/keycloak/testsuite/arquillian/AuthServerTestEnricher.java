/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.keycloak.testsuite.arquillian;

import org.jboss.arquillian.container.spi.Container;
import org.jboss.arquillian.container.spi.ContainerRegistry;
import org.jboss.arquillian.container.spi.event.StartContainer;
import org.jboss.arquillian.container.spi.event.StartSuiteContainers;
import org.jboss.arquillian.container.spi.event.StopContainer;
import org.jboss.arquillian.core.api.Event;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.InstanceProducer;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.api.annotation.Observes;
import org.jboss.arquillian.test.spi.annotation.ClassScoped;
import org.jboss.arquillian.test.spi.annotation.SuiteScoped;
import org.jboss.arquillian.test.spi.event.suite.AfterClass;
import org.jboss.arquillian.test.spi.event.suite.BeforeClass;
import org.jboss.arquillian.test.spi.event.suite.BeforeSuite;
import org.jboss.logging.Logger;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.testsuite.client.KeycloakTestingClient;
import org.keycloak.testsuite.util.LogChecker;
import org.keycloak.testsuite.util.OAuthClient;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.ws.rs.NotFoundException;

/**
 *
 * @author tkyjovsk
 * @author vramik
 */
public class AuthServerTestEnricher {

    protected final Logger log = Logger.getLogger(this.getClass());

    @Inject
    private Instance<ContainerRegistry> containerRegistry;

    @Inject
    private Event<StartContainer> startContainerEvent;
    @Inject
    private Event<StopContainer> stopContainerEvent;

    private static final String AUTH_SERVER_CONTAINER_DEFAULT = "auth-server-undertow";
    private static final String AUTH_SERVER_CONTAINER_PROPERTY = "auth.server.container";
    public static final String AUTH_SERVER_CONTAINER = System.getProperty(AUTH_SERVER_CONTAINER_PROPERTY, AUTH_SERVER_CONTAINER_DEFAULT);

    private static final String AUTH_SERVER_CLUSTER_PROPERTY = "auth.server.cluster";
    public static final boolean AUTH_SERVER_CLUSTER = Boolean.parseBoolean(System.getProperty(AUTH_SERVER_CLUSTER_PROPERTY, "false"));

    private static final boolean AUTH_SERVER_UNDERTOW_CLUSTER = Boolean.parseBoolean(System.getProperty("auth.server.undertow.cluster", "false"));

    private static final Boolean START_MIGRATION_CONTAINER = "auto".equals(System.getProperty("migration.mode")) || 
            "manual".equals(System.getProperty("migration.mode"));

    @Inject
    @SuiteScoped
    private InstanceProducer<SuiteContext> suiteContextProducer;
    private SuiteContext suiteContext;

    @Inject
    @ClassScoped
    private InstanceProducer<TestContext> testContextProducer;

    @Inject
    @ClassScoped
    private InstanceProducer<OAuthClient> oAuthClientProducer;

    public static String getAuthServerContextRoot() {
        return getAuthServerContextRoot(0);
    }

    public static String getAuthServerContextRoot(int clusterPortOffset) {
        String host = System.getProperty("auth.server.host", "localhost");
        int httpPort = Integer.parseInt(System.getProperty("auth.server.http.port")); // property must be set
        int httpsPort = Integer.parseInt(System.getProperty("auth.server.https.port")); // property must be set

        boolean sslRequired = Boolean.parseBoolean(System.getProperty("auth.server.ssl.required"));
        String scheme = sslRequired ? "https" : "http";
        int port = sslRequired ? httpsPort : httpPort;

        return String.format("%s://%s:%s", scheme, host, port + clusterPortOffset);
    }

    public void initializeSuiteContext(@Observes(precedence = 2) BeforeSuite event) {

        Set<ContainerInfo> containers = new LinkedHashSet<>();
        for (Container c : containerRegistry.get().getContainers()) {
            containers.add(new ContainerInfo(c));
        }

        suiteContext = new SuiteContext(containers);

        String authServerFrontend = null;

        if (AUTH_SERVER_CLUSTER) {
            // if cluster mode enabled, load-balancer is the frontend
            for (ContainerInfo c : containers) {
                if (c.getQualifier().startsWith("auth-server-balancer")) {
                    authServerFrontend = c.getQualifier();
                }
            }

            if (authServerFrontend != null) {
                log.info("Using frontend container: " + authServerFrontend);
            } else {
                throw new IllegalStateException("Not found frontend container");
            }
        } else {
            authServerFrontend = AUTH_SERVER_CONTAINER; // single-node mode
        }

        String authServerBackend = AUTH_SERVER_CONTAINER + "-backend";
        int backends = 0;
        for (ContainerInfo container : suiteContext.getContainers()) {
            // frontend
            if (container.getQualifier().equals(authServerFrontend)) {
                updateWithAuthServerInfo(container);
                suiteContext.setAuthServerInfo(container);
            }
            // backends
            if (AUTH_SERVER_CLUSTER && container.getQualifier().startsWith(authServerBackend)) {
                updateWithAuthServerInfo(container, ++backends);
                suiteContext.getAuthServerBackendsInfo().add(container);
            }
        }

        // Setup with 2 undertow backend nodes and no loadbalancer.
//        if (AUTH_SERVER_UNDERTOW_CLUSTER && suiteContext.getAuthServerInfo() == null && !suiteContext.getAuthServerBackendsInfo().isEmpty()) {
//            suiteContext.setAuthServerInfo(suiteContext.getAuthServerBackendsInfo().get(0));
//        }

        // validate auth server setup
        if (suiteContext.getAuthServerInfo() == null) {
            throw new RuntimeException(String.format("No auth server container matching '%s' found in arquillian.xml.", authServerFrontend));
        }
        if (AUTH_SERVER_CLUSTER && suiteContext.getAuthServerBackendsInfo().isEmpty()) {
            throw new RuntimeException(String.format("No auth server container matching '%sN' found in arquillian.xml.", authServerBackend));
        }

        if (START_MIGRATION_CONTAINER) {
            // init migratedAuthServerInfo
            for (ContainerInfo container : suiteContext.getContainers()) {
                // migrated auth server
                if (container.getQualifier().equals("auth-server-jboss-migration")) {
                    updateWithAuthServerInfo(container);
                    suiteContext.setMigratedAuthServerInfo(container);
                }
            }
            // validate setup
            if (suiteContext.getMigratedAuthServerInfo() == null) {
                throw new RuntimeException(String.format("Migration test was enabled but no auth server from which to migrate was activated. "
                        + "A container matching auth-server-jboss-migration needs to be enabled in arquillian.xml."));
            }
        }

        suiteContextProducer.set(suiteContext);
        log.info("\n\n" + suiteContext);
    }

    private ContainerInfo updateWithAuthServerInfo(ContainerInfo authServerInfo) {
        return updateWithAuthServerInfo(authServerInfo, 0);
    }

    private ContainerInfo updateWithAuthServerInfo(ContainerInfo authServerInfo, int clusterPortOffset) {
        try {
            authServerInfo.setContextRoot(new URL(getAuthServerContextRoot(clusterPortOffset)));
        } catch (MalformedURLException ex) {
            throw new IllegalArgumentException(ex);
        }
        return authServerInfo;
    }

    public void startMigratedContainer(@Observes(precedence = 3) StartSuiteContainers event) {
        if (suiteContext.isAuthServerMigrationEnabled()) {
            log.info("\n\n### Starting keycloak " + System.getProperty("migrated.auth.server.version", "- previous") + " ###\n\n");
            startContainerEvent.fire(new StartContainer(suiteContext.getMigratedAuthServerInfo().getArquillianContainer()));
        }
    }

    public void runPreMigrationTask(@Observes(precedence = 2) StartSuiteContainers event) throws Exception {
        if (suiteContext.isAuthServerMigrationEnabled()) {
            log.info("\n\n### Run preMigration task on keycloak " + System.getProperty("migrated.auth.server.version", "- previous") + " ###\n\n");
            suiteContext.getMigrationContext().runPreMigrationTask();
        }
    }

    public void stopMigratedContainer(@Observes(precedence = 1) StartSuiteContainers event) {
        if (suiteContext.isAuthServerMigrationEnabled()) {
            log.info("## STOP old container: " + suiteContext.getMigratedAuthServerInfo().getQualifier());
            stopContainerEvent.fire(new StopContainer(suiteContext.getMigratedAuthServerInfo().getArquillianContainer()));
        }
    }

    public void checkServerLogs(@Observes(precedence = -1) BeforeSuite event) throws IOException, InterruptedException {
        boolean checkLog = Boolean.parseBoolean(System.getProperty("auth.server.log.check", "true"));
        if (checkLog && suiteContext.getAuthServerInfo().isJBossBased()) {
            String jbossHomePath = suiteContext.getAuthServerInfo().getProperties().get("jbossHome");
            LogChecker.checkJBossServerLog(jbossHomePath);
        }
    }

    public void initializeTestContext(@Observes(precedence = 2) BeforeClass event) {
        TestContext testContext = new TestContext(suiteContext, event.getTestClass().getJavaClass());
        testContextProducer.set(testContext);
    }

    public void initializeOAuthClient(@Observes(precedence = 3) BeforeClass event) {
        OAuthClient oAuthClient = new OAuthClient();
        oAuthClientProducer.set(oAuthClient);
    }

    public void afterClass(@Observes(precedence = 2) AfterClass event) {
        TestContext testContext = testContextProducer.get();

        List<RealmRepresentation> testRealmReps = testContext.getTestRealmReps();
        Keycloak adminClient = testContext.getAdminClient();
        KeycloakTestingClient testingClient = testContext.getTestingClient();

        if (testRealmReps != null) {
            log.info("removing test realms after test class");
            for (RealmRepresentation testRealm : testRealmReps) {
                String realmName = testRealm.getRealm();
                log.info("removing realm: " + realmName);
                try {
                    adminClient.realms().realm(realmName).remove();
                } catch (NotFoundException e) {
                    // Ignore
                }
            }
        }

        if (adminClient != null) {
            adminClient.close();
        }

        if (testingClient != null) {
            testingClient.close();
        }
    }

}
