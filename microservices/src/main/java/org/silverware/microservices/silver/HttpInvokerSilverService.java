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
package org.silverware.microservices.silver;

/**
 * Makes it possible to invoke Microservices in the current platform instance remotely.
 *
 * @author Martin Večeřa <marvenec@gmail.com>
 */
public interface HttpInvokerSilverService extends SilverService {

   /**
    * A property with the context path on which the invoker listens.
    * See {@link HttpServerSilverService} to obtain the complete hostname and port.
    */
   String INVOKER_URL = "silverware.http-invoker.url";

   /**
    * Context path extension to denote invocation of a service.
    */
   String INVOKE_COMMAND = "invoke";

   /**
    * Context path extension to denote a query extension.
    */
   String QUERY_COMMAND = "query";

}
