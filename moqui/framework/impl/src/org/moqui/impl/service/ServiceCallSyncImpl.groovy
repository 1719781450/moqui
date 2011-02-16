/*
 * This Work is in the public domain and is provided on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied,
 * including, without limitation, any warranties or conditions of TITLE,
 * NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A PARTICULAR PURPOSE.
 * You are solely responsible for determining the appropriateness of using
 * this Work and assume any risks associated with your use of this Work.
 *
 * This Work includes contributions authored by David E. Jones, not as a
 * "work for hire", who hereby disclaims any copyright to the same.
 */
package org.moqui.impl.service

import javax.transaction.Transaction

import org.moqui.context.TransactionException
import org.moqui.context.TransactionFacade
import org.moqui.service.ServiceCallSync
import org.moqui.impl.entity.EntityDefinition
import org.moqui.impl.service.runner.EntityAutoServiceRunner

class ServiceCallSyncImpl extends ServiceCallImpl implements ServiceCallSync {
    protected boolean requireNewTransaction = false
    /* not supported by Atomikos/etc right now, consider for later: protected int transactionIsolation = -1 */

    ServiceCallSyncImpl(ServiceFacadeImpl sfi) {
        super(sfi)
    }

    @Override
    ServiceCallSync name(String serviceName) { this.setServiceName(serviceName); return this }

    @Override
    ServiceCallSync name(String v, String n) { path = null; verb = v; noun = n; return this }

    @Override
    ServiceCallSync name(String p, String v, String n) { path = p; verb = v; noun = n; return this }

    @Override
    ServiceCallSync parameters(Map<String, Object> map) { parameters.putAll(map); return this }

    @Override
    ServiceCallSync parameter(String name, Object value) { parameters.put(name, value); return this }

    @Override
    ServiceCallSync requireNewTransaction(boolean rnt) { this.requireNewTransaction = rnt; return this }

    /* not supported by Atomikos/etc right now, consider for later:
    @Override
    ServiceCallSync transactionIsolation(int ti) { this.transactionIsolation = ti; return this }
    */

    @Override
    Map<String, Object> call() {
        ServiceDefinition sd = sfi.getServiceDefinition(getServiceName())
        if (!sd) {
            // if verb is create|update|delete and noun is a valid entity name, do an implicit entity-auto
            if ((verb == "create" || verb == "update" || verb == "delete") && sfi.ecfi.entityFacade.getEntityDefinition(noun) != null) {
                return runImplicitEntityAuto()
            } else {
                throw new IllegalArgumentException("Could not find service with name [${getServiceName()}]")
            }
        }

        String type = sd.serviceNode."@type" ?: "inline"
        if (type == "interface") throw new IllegalArgumentException("Cannot run interface service [${getServiceName()}]")

        // start with the settings for the default: use-or-begin
        boolean pauseResumeIfNeeded = false
        boolean beginTransactionIfNeeded = true
        if (requireNewTransaction) {
            // if the setting for this service call is in place, use it regardless of the settings on the service
            pauseResumeIfNeeded = true
        } else {
            if (sd.serviceNode."@transaction" == "ignore") {
                beginTransactionIfNeeded = false
            } else if (sd.serviceNode."@transaction" == "force-new") {
                pauseResumeIfNeeded = true
            }
        }

        Integer transactionTimeout = null
        if (sd.serviceNode."@transaction-timeout") {
            transactionTimeout = sd.serviceNode."@transaction-timeout" as Integer
        }

        ServiceRunner sr = sfi.getServiceRunner(type)
        if (!sr) throw new IllegalArgumentException("Could not find service runner for type [${type}] for service [${getServiceName()}]")

        // TODO trigger SECAs

        // TODO validation (sd.serviceNode."@validate")
        // TODO: if service is to be validated, go through service in-parameters definition and only get valid parameters
        // TODO: do type conversions as needed for matching in parameters

        // TODO authentication (sd.serviceNode."@authenticate")

        // TODO sd.serviceNode."@debug"
        // TODO sd.serviceNode."@semaphore"

        TransactionFacade tf = sfi.ecfi.getTransactionFacade()
        Transaction parentTransaction = null
        Map<String, Object> result = null
        try {
            if (pauseResumeIfNeeded && tf.isTransactionInPlace()) parentTransaction = tf.suspend()
            boolean beganTransaction = beginTransactionIfNeeded ? tf.begin(transactionTimeout) : false
            try {
                result = sr.runService(sd, this.parameters)
            } catch (Throwable t) {
                tf.rollback(beganTransaction, "Error getting primary sequenced ID", t)
            } finally {
                if (tf.isTransactionInPlace()) tf.commit(beganTransaction)
            }
        } catch (TransactionException e) {
            throw e
        } finally {
            if (parentTransaction != null) tf.resume(parentTransaction)
        }
        return result
    }

    protected Map<String, Object> runImplicitEntityAuto() {
        // TODO trigger SECAs
        // NOTE: no authentication, assume not required for this; security settings can override this and require
        //     permissions, which will require authentication
        TransactionFacade tf = sfi.ecfi.getTransactionFacade()
        Transaction parentTransaction = null
        Map<String, Object> result = new HashMap()
        try {
            if (requireNewTransaction && tf.isTransactionInPlace()) parentTransaction = tf.suspend()
            boolean beganTransaction = tf.begin(null)
            try {
                EntityDefinition ed = sfi.ecfi.entityFacade.getEntityDefinition(noun)
                if (verb == "create") {
                    EntityAutoServiceRunner.createEntity(sfi, ed, parameters, result, null)
                } else if (verb == "update") {
                    EntityAutoServiceRunner.updateEntity(sfi, ed, parameters, result, null)
                } else if (verb == "delete") {
                    EntityAutoServiceRunner.deleteEntity(sfi, ed, parameters)
                }
            } catch (Throwable t) {
                tf.rollback(beganTransaction, "Error getting primary sequenced ID", t)
            } finally {
                if (tf.isTransactionInPlace()) tf.commit(beganTransaction)
            }
        } catch (TransactionException e) {
            throw e
        } finally {
            if (parentTransaction != null) tf.resume(parentTransaction)
        }
        return result
    }
}
