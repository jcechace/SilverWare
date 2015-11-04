/*
 * -----------------------------------------------------------------------\
 * SilverWare
 *  
 * Copyright (C) 2010 - 2013 the original author or authors.
 *  
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
 * -----------------------------------------------------------------------/
 */
package io.silverware.microservices.providers.cdi;

import io.silverware.microservices.Context;
import io.silverware.microservices.MicroserviceMetaData;
import io.silverware.microservices.annotations.Microservice;
import io.silverware.microservices.annotations.MicroserviceReference;
import io.silverware.microservices.providers.MicroserviceProvider;
import io.silverware.microservices.providers.cdi.builtin.Configuration;
import io.silverware.microservices.providers.cdi.builtin.Storage;
import io.silverware.microservices.providers.cdi.internal.MicroserviceProxyBean;
import io.silverware.microservices.providers.cdi.internal.MicroservicesCDIExtension;
import io.silverware.microservices.providers.cdi.internal.RestInterface;
import io.silverware.microservices.silver.CdiSilverService;
import io.silverware.microservices.util.Utils;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jboss.weld.environment.se.Weld;
import org.jboss.weld.environment.se.WeldContainer;

import java.lang.annotation.Annotation;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.annotation.Priority;
import javax.enterprise.inject.spi.Bean;
import javax.enterprise.inject.spi.BeanManager;
import javax.enterprise.util.AnnotationLiteral;
import javax.interceptor.AroundInvoke;
import javax.interceptor.Interceptor;
import javax.interceptor.InvocationContext;

/**
 * @author Martin Večeřa <marvenec@gmail.com>
 */
public class CdiMicroserviceProvider implements MicroserviceProvider, CdiSilverService {

   /**
    * Logger.
    */
   private static final Logger log = LogManager.getLogger(CdiMicroserviceProvider.class);

   /**
    * Microservices context.
    */
   private Context context;

   @Override
   public void initialize(final Context context) {
      this.context = context;
   }

   @Override
   public Context getContext() {
      return context;
   }

   @Override
   public void run() {
      try {
         log.info("Hello from CDI microservice provider!");

         final Weld weld = new Weld();
         final RestInterface rest = new RestInterface(context);
         final MicroservicesCDIExtension microservicesCDIExtension = new MicroservicesCDIExtension(context, rest);
         System.setProperty("org.jboss.weld.se.archive.isolation", "false");
         weld.addExtension(microservicesCDIExtension);

         final WeldContainer container = weld.initialize();
         context.getProperties().put(BEAN_MANAGER, container.getBeanManager());
         context.getProperties().put(CDI_CONTAINER, container);
         context.getProperties().put(Storage.STORAGE, new HashMap<String, Object>());

         log.info("Discovered the following microservice implementations:");
         context.getMicroservices().forEach(metaData -> log.info(" - " + metaData.toString()));

         log.info("Total count of discovered microservice injection points: " + microservicesCDIExtension.getInjectionPointsCount());

         container.event().select(MicroservicesStartedEvent.class).fire(new MicroservicesStartedEvent(context, container.getBeanManager(), container));

         log.info("Deploying REST gateway services.");
         rest.deploy();

         try {
            while (!Thread.currentThread().isInterrupted()) {
               Thread.sleep(1000);
            }
         } catch (InterruptedException ie) {
            Utils.shutdownLog(log, ie);
         } finally {
            rest.undeploy();
            weld.shutdown();
         }
      } catch (Exception e) {
         log.error("CDI microservice provider failed: ", e);
      }
   }

   public Set<Object> lookupMicroservice(final MicroserviceMetaData microserviceMetaData) {
      final String name = microserviceMetaData.getName();
      final Class<?> type = microserviceMetaData.getType();
      final Set<Annotation> qualifiers = microserviceMetaData.getQualifiers();
      final Set<Object> matchingBeansByName = new HashSet<>();
      final Set<Object> matchingBeansByType = new HashSet<>();
      boolean wasAlternative = false;

      /*
         We are in search for a CDI bean that meets the provided meta-data.
         Input and corresponding output is as follows:
           * name specified in MicroserviceReference or derived from data type (both class or interface)
                 * beans having the same name in Microservice annotation
                 * beans having the same name according to CDI
           * the injection point is interface and name matches interface type name
                 * beans implementing the interface and matching the qualifiers
         In all cases, there must be precisely one result or an error is thrown.
       */

      final BeanManager beanManager = ((BeanManager) context.getProperties().get(BEAN_MANAGER));
      Set<Bean<?>> beans = beanManager.getBeans(type, qualifiers.toArray(new Annotation[qualifiers.size()]));
      for (Bean<?> bean : beans) {
         if (bean.getBeanClass().isAnnotationPresent(Microservice.class) && !(bean instanceof MicroserviceProxyBean)) {
            final Bean<?> theBean = beanManager.resolve(Collections.singleton(bean));
            final Microservice microserviceAnnotation = theBean.getBeanClass().getAnnotation(Microservice.class);

            if (name != null) {
               if ((!microserviceAnnotation.value().isEmpty() && name.equals(microserviceAnnotation.value())) ||
                     (microserviceAnnotation.value().isEmpty() && name.equals(theBean.getName()))) {
                  matchingBeansByName.add(beanManager.getReference(theBean, type, beanManager.createCreationalContext(theBean)));
               } else if (type.isAssignableFrom(theBean.getBeanClass())) {
                  final Set<Annotation> qualifiersToCompare = new HashSet<>(theBean.getQualifiers());
                  qualifiers.stream().forEach(qualifiersToCompare::remove);

                  if (qualifiersToCompare.size() == 0) {

                     if (bean.isAlternative()) {
                        if (!wasAlternative) {
                           matchingBeansByType.clear();
                           matchingBeansByType.add(beanManager.getReference(theBean, type, beanManager.createCreationalContext(theBean)));
                           wasAlternative = true;
                        } else {
                           matchingBeansByType.add(beanManager.getReference(theBean, type, beanManager.createCreationalContext(theBean)));
                           throw new IllegalStateException(String.format("There are more than alternate beans matching the query: %s. The beans are: %s.", microserviceMetaData.toString(), matchingBeansByType.toString()));
                        }
                     } else {
                        if (!wasAlternative) {
                           matchingBeansByType.add(beanManager.getReference(theBean, type, beanManager.createCreationalContext(theBean)));
                        } else {
                           // ignore this bean
                        }
                     }
                  }
               }
            }
         }
      }

      if (matchingBeansByName.size() == 1) {
         return matchingBeansByName;
      }

      if (matchingBeansByName.size() > 1 || matchingBeansByType.size() > 1) {
         throw new IllegalStateException(String.format("There are more than one beans matching the query: %s. The beans are: %s.", microserviceMetaData.toString(), matchingBeansByType.toString()));
      }

      // now we know that matchingBeansByType.size() <= 1
      return matchingBeansByType;
   }

   @Override
   public Set<Object> lookupLocalMicroservice(final MicroserviceMetaData metaData) {
      return lookupMicroservice(metaData);
   }

   static Object getMicroserviceProxy(final Context context, final Class clazz) {
      return ((WeldContainer) context.getProperties().get(CDI_CONTAINER)).instance().select(clazz).select(new MicroserviceReferenceLiteral("")).get();
   }

   static Object getMicroserviceProxy(final Context context, final Class clazz, final String beanName) {
      return ((WeldContainer) context.getProperties().get(CDI_CONTAINER)).instance().select(clazz).select(new MicroserviceReferenceLiteral(beanName)).get();
   }

   private static class MicroserviceReferenceLiteral extends AnnotationLiteral<MicroserviceReference> implements MicroserviceReference {

      private final String name;

      public MicroserviceReferenceLiteral(final String name) {
         this.name = name;
      }

      @Override
      public String value() {
         return name;
      }
   }

   @Interceptor()
   @Priority(Interceptor.Priority.APPLICATION)
   public static class LoggingInterceptor {

      @AroundInvoke
      public Object log(final InvocationContext ic) throws Exception {
         log.info("AroundInvoke " + ic.toString());
         return ic.proceed();
      }
   }

   @SuppressWarnings("unused")
   @Microservice
   public class SilverWareConfiguration implements Configuration {

      @Override
      public Object getProperty(final String propertyName) {
         return context.getProperties().get(propertyName);
      }
   }

   @SuppressWarnings({"unused", "unchecked"})
   @Microservice
   public class SilverWareStorage implements Storage {

      private Map<String, Object> getStorage() {
         return (Map<String, Object>) context.getProperties().get(STORAGE);
      }

      @Override
      public void put(final String key, final Object value) {
         getStorage().put(key, value);
      }

      @Override
      public Object get(final String key) {
         return getStorage().get(key);
      }

      @Override
      public boolean drop(final String key) {
         return getStorage().remove(key) != null;
      }
   }
}