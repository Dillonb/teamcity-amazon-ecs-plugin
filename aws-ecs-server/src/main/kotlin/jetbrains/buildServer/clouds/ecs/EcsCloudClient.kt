package jetbrains.buildServer.clouds.ecs

import com.google.common.collect.Maps
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.clouds.*
import jetbrains.buildServer.clouds.ecs.apiConnector.EcsApiConnector
import jetbrains.buildServer.serverSide.AgentDescription
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.collections.HashMap

class EcsCloudClient(images: List<EcsCloudImage>,
                     val apiConnector: EcsApiConnector,
                     val ecsClientParams: EcsCloudClientParameters,
                     private val serverUuid: String,
                     private val cloudProfileId: String) : CloudClientEx {
    private val LOG = Logger.getInstance(EcsCloudClient::class.java.getName())

    private var myCurrentlyRunningInstancesCount: Int = 0
    private var myCurrentError: CloudErrorInfo? = null
    private var myImageIdToImageMap: ConcurrentHashMap<String, EcsCloudImage> = ConcurrentHashMap(Maps.uniqueIndex(images, { it?.id }))


    override fun isInitialized(): Boolean {
        //TODO: wait while all images populate list of their instances
        return true
    }

    override fun dispose() {
        LOG.debug("EcsCloudClient disposed")
    }

    override fun getErrorInfo(): CloudErrorInfo? {
        return myCurrentError
    }

    override fun canStartNewInstance(image: CloudImage): Boolean {
        val kubeCloudImage = image as EcsCloudImage
        val kubeCloudImageId = kubeCloudImage.getId()
        if (!myImageIdToImageMap.containsKey(kubeCloudImageId)) {
            LOG.debug("Can't start instance of unknown cloud image with id " + kubeCloudImageId)
            return false
        }
        val profileInstanceLimit = ecsClientParams.instanceLimit
        if (profileInstanceLimit > 0 && myCurrentlyRunningInstancesCount >= profileInstanceLimit)
            return false

        val imageLimit = kubeCloudImage.instanceLimit
        return imageLimit <= 0 || kubeCloudImage.instanceCount < imageLimit
    }

    override fun startNewInstance(image: CloudImage, tag: CloudInstanceUserData): CloudInstance {
        try{
            val ecsImage = image as EcsCloudImage
            val taskDefinition = apiConnector.describeTaskDefinition(ecsImage.taskDefinition) ?: throw CloudException("""Task definition ${ecsImage.taskDefinition} is missing""")
            val instanceId = taskDefinition.generateNewInstanceId()

            val additionalEnvironment = HashMap<String, String>()
            additionalEnvironment.put(SERVER_UUID_ECS_ENV, serverUuid)
            additionalEnvironment.put(SERVER_URL_ECS_ENV, tag.serverAddress)
            additionalEnvironment.put(OFFICIAL_IMAGE_SERVER_URL_ECS_ENV, tag.serverAddress)
            additionalEnvironment.put(PROFILE_ID_ECS_ENV, tag.profileId)
            additionalEnvironment.put(IMAGE_ID_ECS_ENV, image.id)
            additionalEnvironment.put(INSTANCE_ID_ECS_ENV, instanceId)
            additionalEnvironment.put(PROPOSED_AGENT_NAME_AGENT_PROP, ecsImage.generateAgentName(instanceId))

            val tasks = apiConnector.runTask(taskDefinition, ecsImage.cluster, ecsImage.taskGroup, additionalEnvironment, startedByTeamCity(serverUuid))
            val newInstance = EcsCloudInstanceImpl(instanceId, ecsImage, tasks[0], apiConnector)
            ecsImage.addInstance(newInstance)
            myCurrentError = null
            myCurrentlyRunningInstancesCount++
            return newInstance
        } catch (ex: Exception){
            LOG.debug("Failed to start cloud instance", ex)
            myCurrentError = CloudErrorInfo("Failed to start cloud instance", ex.localizedMessage, ex)
            throw ex
        }
    }

    override fun terminateInstance(instance: CloudInstance) {
        val kubeCloudInstance = instance as EcsCloudInstance
        kubeCloudInstance.terminate()
        myCurrentlyRunningInstancesCount--
    }

    override fun restartInstance(instance: CloudInstance) {
        throw UnsupportedOperationException("Restart not implemented")
    }

    override fun generateAgentName(agent: AgentDescription): String? {
        return agent.availableParameters.get(PROPOSED_AGENT_NAME_AGENT_PROP)
    }

    override fun getImages(): MutableCollection<out CloudImage> {
        return Collections.unmodifiableCollection(myImageIdToImageMap.values)
    }

    override fun findImageById(imageId: String): CloudImage? {
        return myImageIdToImageMap.get(imageId)
    }

    override fun findInstanceByAgent(agent: AgentDescription): CloudInstance? {
        val agentParameters = agent.getAvailableParameters()

        if (serverUuid != agentParameters.get(SERVER_UUID_AGENT_PROP) || cloudProfileId != agentParameters.get(PROFILE_ID_AGENT_PROP))
            return null

        val imageId = agentParameters.get(IMAGE_ID_AGENT_PROP)
        val instanceId = agentParameters.get(INSTANCE_ID_AGENT_PROP)
        if (imageId != null && instanceId != null) {
            val cloudImage = myImageIdToImageMap[imageId]
            if (cloudImage != null) {
                return cloudImage.findInstanceById(instanceId)
            }
        }
        return null
    }
}