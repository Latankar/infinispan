package org.infinispan.server.core;

import java.net.InetSocketAddress;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.management.DynamicMBean;
import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.infinispan.IllegalLifecycleStateException;
import org.infinispan.commons.CacheException;
import org.infinispan.commons.api.BasicCacheContainer;
import org.infinispan.commons.jmx.JmxUtil;
import org.infinispan.commons.logging.LogFactory;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalJmxStatisticsConfiguration;
import org.infinispan.factories.components.ManageableComponentMetadata;
import org.infinispan.jmx.ResourceDMBean;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.server.core.configuration.ProtocolServerConfiguration;
import org.infinispan.server.core.logging.Log;
import org.infinispan.server.core.transport.NettyTransport;
import org.infinispan.server.core.utils.ManageableThreadPoolExecutorService;
import org.infinispan.tasks.TaskManager;

import io.netty.util.concurrent.DefaultThreadFactory;

/**
 * A common protocol server dealing with common property parameter validation and assignment and transport lifecycle.
 *
 * @author Galder Zamarreño
 * @author wburns
 * @since 4.1
 */
public abstract class AbstractProtocolServer<A extends ProtocolServerConfiguration> extends AbstractCacheIgnoreAware
      implements ProtocolServer<A> {

   private static final Log log = LogFactory.getLog(AbstractProtocolServer.class, Log.class);

   private final String protocolName;

   protected NettyTransport transport;
   protected EmbeddedCacheManager cacheManager;
   protected A configuration;
   private ObjectName transportObjName;
   private MBeanServer mbeanServer;
   private ThreadPoolExecutor executor;
   private ObjectName executorObjName;


   protected AbstractProtocolServer(String protocolName) {
      this.protocolName = protocolName;
   }

   @Override
   public String getName() {
      return protocolName;
   }

   protected void startInternal(A configuration, EmbeddedCacheManager cacheManager) {
      this.configuration = configuration;
      this.cacheManager = cacheManager;

      if (log.isDebugEnabled()) {
         log.debugf("Starting server with configuration: %s", configuration);
      }

      registerAdminOperationsHandler();

      // Start default cache
      startDefaultCache();

      if(configuration.startTransport())
         startTransport();
   }

   private void registerAdminOperationsHandler() {
      if (configuration.adminOperationsHandler() != null) {
         TaskManager taskManager = SecurityActions.getGlobalComponentRegistry(cacheManager).getComponent(TaskManager.class);
         if (taskManager != null) {
            taskManager.registerTaskEngine(configuration.adminOperationsHandler());
         } else {
            throw log.cannotRegisterAdminOperationsHandler();
         }
      }
   }

   @Override
   public final void start(A configuration, EmbeddedCacheManager cacheManager) {
      try {
         configuration.ignoredCaches().forEach(this::ignoreCache);
         startInternal(configuration, cacheManager);
      } catch (RuntimeException t) {
         stop();
         throw t;
      }
   }

   protected void startTransport() {
      InetSocketAddress address = new InetSocketAddress(configuration.host(), configuration.port());
      transport = new NettyTransport(address, configuration, getQualifiedName(), cacheManager);
      transport.initializeHandler(getInitializer());

      // Register transport and worker MBeans regardless
      registerServerMBeans();

      try {
         transport.start();
      } catch (Throwable re) {
         try {
            unregisterServerMBeans();
         } catch (Exception e) {
            throw new CacheException(e);
         }
         throw re;
      }
   }

   protected ThreadPoolExecutor getExecutor() {
      if (this.executor == null || this.executor.isShutdown()) {
         DefaultThreadFactory factory = new DefaultThreadFactory(getQualifiedName() + "-ServerHandler");
         int workerThreads = getWorkerThreads();
         this.executor = new ThreadPoolExecutor(
               workerThreads,
               workerThreads,
               0L, TimeUnit.MILLISECONDS,
               new LinkedBlockingQueue<>(),
               factory,
               abortPolicy);
      }
      return executor;
   }

   private ThreadPoolExecutor.AbortPolicy abortPolicy = new ThreadPoolExecutor.AbortPolicy() {
      @Override
      public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
         if (executor.isShutdown())
            throw new IllegalLifecycleStateException("Server has been stopped");
         else
            super.rejectedExecution(r, e);
      }
   };

   protected void registerServerMBeans() {
      GlobalConfiguration globalCfg = cacheManager.getCacheManagerConfiguration();
      GlobalJmxStatisticsConfiguration jmxConfig = globalCfg.globalJmxStatistics();
      if (jmxConfig.enabled()) {
         mbeanServer = JmxUtil.lookupMBeanServer(jmxConfig.mbeanServerLookup(), jmxConfig.properties());
         String groupName = String.format("type=Server,name=%s", getQualifiedName());
         String jmxDomain = JmxUtil.buildJmxDomain(jmxConfig.domain(), mbeanServer, groupName);

         try {
            transportObjName = registerMBean(transport, jmxDomain, groupName, null);
            executorObjName = registerMBean(new ManageableThreadPoolExecutorService(getExecutor()), jmxDomain, groupName, "WorkerExecutor");
         } catch (Exception e) {
            throw new RuntimeException(e);
         }
      }
   }

   private ObjectName registerMBean(Object instance, String jmxDomain, String groupName, String name) throws Exception {
      // Pick up metadata from the component metadata repository
      ManageableComponentMetadata meta = LifecycleCallbacks.componentMetadataRepo
            .findComponentMetadata(instance.getClass()).toManageableComponentMetadata();
      DynamicMBean dynamicMBean = new ResourceDMBean(instance, meta);
      ObjectName objectName = new ObjectName(String.format("%s:%s,component=%s", jmxDomain, groupName, name != null ? name : meta.getJmxObjectName()));
      JmxUtil.registerMBean(dynamicMBean, objectName, mbeanServer);
      return objectName;
   }

   protected void unregisterServerMBeans() throws Exception {
      // Unregister mbean(s)
      if (transportObjName != null)
         JmxUtil.unregisterMBean(transportObjName, mbeanServer);
      if (executorObjName != null)
         JmxUtil.unregisterMBean(executorObjName, mbeanServer);
   }

   public String getQualifiedName() {
      return protocolName + (configuration.name().length() > 0 ? "-" : "") + configuration.name();
   }

   @Override
   public void stop() {
      boolean isDebug = log.isDebugEnabled();
      if (isDebug && configuration != null)
         log.debugf("Stopping server listening in %s:%d", configuration.host(), configuration.port());

      if (executor != null) executor.shutdownNow();

      if (transport != null)
         transport.stop();

      try {
         unregisterServerMBeans();
      } catch (Exception e) {
         throw new CacheException(e);
      }

      if (isDebug)
         log.debug("Server stopped");
   }

   public EmbeddedCacheManager getCacheManager() {
      return cacheManager;
   }

   public String getHost() {
      return configuration.host();
   }

   public Integer getPort() {
      if (transport != null) {
         return transport.getPort();
      }
      return configuration.port();
   }

   @Override
   public A getConfiguration() {
      return configuration;
   }

   protected void startDefaultCache() {
      cacheManager.getCache(defaultCacheName());
   }

   public String defaultCacheName() {
      if (configuration.defaultCacheName() != null) {
         return configuration.defaultCacheName();
      } else {
         return cacheManager.getCacheManagerConfiguration().defaultCacheName().orElse(BasicCacheContainer.DEFAULT_CACHE_NAME);
      }
   }

   public boolean isTransportEnabled() {
      return transport != null;
   }

   public NettyTransport getTransport() {
      return transport;
   }

   /**
    * @deprecated Use the {@link #getExecutor()} to obtain information about the worker executor instead
    */
   @Deprecated
   public abstract int getWorkerThreads();
}
