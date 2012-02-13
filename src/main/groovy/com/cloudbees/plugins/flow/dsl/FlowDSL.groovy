/*
 * Copyright (C) 2011 CloudBees Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * along with this program; if not, see <http://www.gnu.org/licenses/>.
 */

package com.cloudbees.plugins.flow.dsl

import jenkins.model.Jenkins
import java.util.logging.Logger
import hudson.model.Item
import hudson.model.AbstractProject
import hudson.model.AbstractBuild
import java.util.concurrent.Future
import hudson.model.Cause
import hudson.model.Result
import com.cloudbees.plugins.flow.JobNotFoundException
import hudson.model.ParametersAction
import hudson.model.BooleanParameterValue
import hudson.model.Action
import hudson.model.StringParameterValue;

public class FlowDSL {

    private ExpandoMetaClass createEMC(Class scriptClass, Closure cl) {
        ExpandoMetaClass emc = new ExpandoMetaClass(scriptClass, false)
        cl(emc)
        emc.initialize()
        return emc
    }

    def Result executeFlowScript(String dsl, Cause cause) {
        // TODO : add all restrictions, etc ...
        FlowDelegate flow = new FlowDelegate(cause)
        Script dslScript = new GroovyShell().parse(dsl)
        dslScript.metaClass = createEMC(dslScript.class, {
            ExpandoMetaClass emc ->
            emc.flow = {
                Closure cl ->
                cl.delegate = flow
                cl.resolveStrategy = Closure.DELEGATE_FIRST
                cl()
            }
        })
        dslScript.run()
        def ret = flow.failed()
        flow.cleanAfterRun()
        return ret
    }
}

public class FlowDelegate {

    private static final Logger LOGGER = Logger.getLogger(FlowDelegate.class.getName());

    private ThreadLocal<Boolean> parallel = new ThreadLocal<Boolean>()
    private ThreadLocal<List<JobInvocation>> parallelJobs = new ThreadLocal<List<JobInvocation>>()
    private ThreadLocal<List<String>> failuresContext = new ThreadLocal<List<String>>()
    private ThreadLocal<Boolean> retryContext = new ThreadLocal<Boolean>()

    def Cause cause

    public FlowDelegate(Cause c) {
        cause = c
        parallel.set(false)
        parallelJobs.set(new ArrayList<String>())
        failuresContext.set(new ArrayList<String>())
        retryContext.set(false)
    }

    def failed() {
        // TODO : return the right Result based on failures priority
        if (failuresContext.get().isEmpty()) {
            return Result.SUCCESS
        }
        return Result.FAILURE
    }

    def build(String jobName) {
        if (failuresContext.get().isEmpty()) {
            executeJenkinsJobWithName(jobName);
        }
    }

    def build(Map args, String jobName) {
        if (failuresContext.get().isEmpty()) {
            executeJenkinsJobWithNameAndArgs(jobName, args);
        }
    }

    def parallel(closure) {
        // TODO : handle parallel inside parallel
        if (failuresContext.get().isEmpty()) {
            Map<String, JobInvocation> results = new HashMap<String, JobInvocation>()
            List<JobInvocation> oldJobs = new ArrayList<JobInvocation>()
            if (parallel.get()) {
                oldJobs = parallelJobs.get()
                parallelJobs.set(new ArrayList<JobInvocation>())
            }
            if (parallel.get()) {
                throw new RuntimeException("You can't use 'parallel' inside a 'parallel' block")
            }
            parallel.set(true);
            println "Parallel execution {"
            closure()
            for (JobInvocation job : parallelJobs.get()) {
                results.put(job.name, job)//job.runAndContinue())
            }
            println "}"
            println "Waiting for jobs : ${parallelJobs.get()}"
            parallelJobs.get().clear()
            if (!oldJobs.isEmpty()) {
                parallelJobs.set(oldJobs)
            }
            parallel.set(false);
            results.values().each {
                // TODO : enhance it
                // TODO : remove Jenkins dependency
                AbstractBuild<?, ?> build = it.future().get()
                if (build.getResult() != Result.SUCCESS) {
                    failuresContext.get().add(it.result())
                }
            }
            return results
        }
    }

    def guard(guardedClosure) {
        def deleg = this;
        [ rescue : { rescueClosure ->
            rescueClosure.delegate = deleg
            rescueClosure.resolveStrategy = Closure.DELEGATE_FIRST
            if (failuresContext.get().isEmpty()) {
                //List<String> oldContext = failuresContext.get()
                failuresContext.set(new ArrayList<String>())
                println "Guarded {"
                try {
                    guardedClosure()
                } catch (Throwable t) {
                    // Do we need to do something here ?
                }
                print "}"
                //if (failuresContext.get().isEmpty()) { // TODO : check if we have to do try/catch or try/finally
                //List<String> oldRescureContext = failuresContext.get()
                failuresContext.set(new ArrayList<String>())
                println " Rescuing {"
                rescueClosure()
                println "}"
                //}
                //failuresContext.set(oldRescureContext.addAll(failuresContext.get()))
                //println ""
                //failuresContext.set(oldContext)
                //failuresContext.set(new ArrayList<String>())
            }
        } ]
    }

    // TODO use guard keyword instead, dunno how to do it
    def retry(retryClosure) {
        retryContext.set(true)
        return {
            if (retryContext.get()) {
                retryContext.set(false)
                retryClosure()
                if (!failuresContext.get().isEmpty()) {
                    retryContext.set(true)
                    failuresContext.get().clear()
                }
                // TODO : here handle failure context cleaning
            }
        }
    }

    private def executeJenkinsJobWithName(String name) {
        if (failuresContext.get().isEmpty()) {
            return executeJenkinsJobWithNameAndArgs(name, [:])
        }
    }

    private def executeJenkinsJobWithNameAndArgs(String name, Map args) {
        if (failuresContext.get().isEmpty()) {
            // ask for job with name ${name}
            JobInvocation job = findJob(name, args, cause)
            if (parallel.get()) {
                // if parallel enabled, push the job in a threadlocal list and let other run it for you
                job.runAndContinue()
                parallelJobs.get().add(job)
            } else {
                job.runAndWait()
            }
            if (job.result() != Result.SUCCESS) {
                failuresContext.get().add(job.result())
            }
            return job;
        }
    }

    private def findJob(String name, Map args, Cause cause) {
        return new JobInvocation(name, args, cause)
    }

    private def cleanAfterRun() {
        parallel.remove()
        parallelJobs.remove()
        failuresContext.remove()
        retryContext.remove()
    }

    def propertyMissing(String name) {
        throw new MissingPropertyException("Property ${name} doesn't exist."); // TODO : add the DSL grammar for help
    }

    def methodMissing(String name, Object args) {
        throw new MissingMethodException("Method ${name} doesn't exist."); // TODO : add the DSL grammar for help
    }
    // TODO : add restrictions for System.exit, etc ...
}

public class JobInvocation {

    def String name
    def Map args
    // TODO : remove Jenkins dependency
    def Cause cause
    def AbstractProject<?, ? extends AbstractBuild<?, ?>> project
    def AbstractBuild build
    def Result result = Result.SUCCESS
    def Future<? extends AbstractBuild<?, ?>> future
    // TODO : remove Jenkins dependency

    public JobInvocation(String name, Map args, Cause cause) {
        this.name = name
        this.args = args
        this.cause = cause
        // TODO : remove Jenkins dependency
        Item item = Jenkins.getInstance().getItem(name);
        if (item instanceof AbstractProject) {
            project = (AbstractProject<?, ? extends AbstractBuild<?,?>>) item;
        } else {
            throw new JobNotFoundException("Item '${name}' not found (or isn't a job).")
        }
    }

    def runAndWait() {
        // TODO : remove Jenkins dependency
        future = project.scheduleBuild2(project.getQuietPeriod(), cause, getActions());
        println "Jenkins is running job : ${name} with args : ${args} and blocking"
        build = future.get();
        result = build.getResult();
        return this;
    }

    def runAndContinue() {
        // TODO : remove Jenkins dependency
        future = project.scheduleBuild2(project.getQuietPeriod(), cause, getActions());
        println "Jenkins is running job : ${name} with args : ${args} and continuing"
        return this;
    }

    def result() {
        return result;
    }

    def build() {
        if (build == null) {
            build = future.get()
        }
        return build
    }

    def future() {
        return future
    }

    def String toString() {
        return "Job : ${name} with ${args}"
    }

    def getActions() {
        List<Action> actions = new ArrayList<Action>();
        for (Object param: args) {
            String paramName = param.key
            Object paramValue = param.value
            if (paramValue instanceof Closure) {
                paramValue = getClosureValue(paramValue)
            }
            if (paramValue instanceof Boolean) {
                actions.add(new ParametersAction(new BooleanParameterValue(paramName, (Boolean) paramValue)))
            }
            else {
                //TODO For now we will only support String and boolean parameters
                actions.add(new ParametersAction(new StringParameterValue(paramName, paramValue.toString())))
            }
        }
        return actions
    }

    def getClosureValue(closure) {
        return closure()
    }
}