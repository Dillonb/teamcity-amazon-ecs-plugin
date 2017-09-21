package jetbrains.buildServer.clouds.ecs

import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.clouds.CloudErrorInfo
import jetbrains.buildServer.clouds.CloudInstance
import jetbrains.buildServer.clouds.ecs.apiConnector.EcsApiConnector
import java.util.concurrent.ConcurrentHashMap

class EcsCloudImageImpl(private val imageData: EcsCloudImageData, private val apiConnector: EcsApiConnector) : EcsCloudImage {
    private val LOG = Logger.getInstance(EcsCloudImageImpl::class.java.getName())

    private val myIdToInstanceMap = ConcurrentHashMap<String, EcsCloudInstance>()
    private var myCurrentError: CloudErrorInfo? = null

    override val instanceLimit: Int
        get() = imageData.instanceLimit

    override val instanceCount: Int
        get() = myIdToInstanceMap.size

    override val taskDefinition: String
        get() = imageData.taskDefinition

    override val cluster: String?
        get() = imageData.cluster

    override val taskGroup: String?
        get() = imageData.taskGroup

    override fun getAgentPoolId(): Int? {
        return imageData.agentPoolId
    }

    override fun getName(): String {
        return taskDefinition
    }

    override fun getId(): String {
        return imageData.id
    }

    override fun getInstances(): MutableCollection<out CloudInstance> {
        return myIdToInstanceMap.values
    }

    override fun getErrorInfo(): CloudErrorInfo? {
        return myCurrentError
    }

    override fun findInstanceById(id: String): CloudInstance? {
        return myIdToInstanceMap[id]
    }

    override fun populateInstances() {
        try {
            for (taskArn in apiConnector.listTasks(cluster)) {
                val task = apiConnector.describeTask(taskArn)
                if(task != null){
                    val cloudInstance = EcsCloudInstanceImpl(this, task, apiConnector)
                    myIdToInstanceMap.put(cloudInstance.getInstanceId(), cloudInstance)
                } else {
                    LOG.warn("Failed to describe ECS task with arn $taskArn")
                }
            }
            myCurrentError = null
        } catch (ex: Throwable) {
            myCurrentError = CloudErrorInfo("Failed populate instances", ex.message.toString(), ex)
            throw ex
        }

    }

    override fun addInstance(instance: EcsCloudInstance) {
        myIdToInstanceMap.put(instance.instanceId, instance)
    }

    override fun deleteInstance(instance: EcsCloudInstance) {
        myIdToInstanceMap.remove(instance.instanceId)
    }
}