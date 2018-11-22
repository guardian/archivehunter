package services

import clientManagers.ECSClientManager
import com.amazonaws.services.ecs.model._
import javax.inject.Inject
import play.api.{Configuration, Logger}

import collection.JavaConverters._
import scala.util.{Failure, Success}

class ContainerTaskManager @Inject() (config:Configuration, ecsClientManager: ECSClientManager){
  private val logger = Logger(getClass)
  private val profileName = config.getOptional[String]("externalData.awsProfile")
  private val taskDefinitionName = config.get[String]("proxies.ecsTaskDefinitionName")
  private val taskContainerName = config.get[String]("proxies.taskContainerName")
  private val subnets = config.get[String]("ecs.subnets").split(",").toSeq

  def runTask(command:Seq[String], environment:Map[String,String], name:String, cpu:Option[Int]=None) = {
    val client = ecsClientManager.getClient(profileName)

    val actualCpu = cpu.getOrElse(1)
    val actualEnvironment = environment.map(entry=>new KeyValuePair().withName(entry._1).withValue(entry._2)).asJavaCollection

    val overrides = new TaskOverride().withContainerOverrides(new ContainerOverride()
      .withCommand(command.asJava)
      .withCpu(actualCpu)
      .withEnvironment(actualEnvironment)
        .withName(taskContainerName)
    )

    //external IP is needed to pull images from Docker Hub
    val netConfig = new NetworkConfiguration().withAwsvpcConfiguration(new AwsVpcConfiguration().withSubnets(subnets.asJava).withAssignPublicIp(AssignPublicIp.ENABLED))

    val rq = new RunTaskRequest()
      .withCluster(config.get[String]("ecs.cluster"))
      .withTaskDefinition(taskDefinitionName)
      .withOverrides(overrides)
      .withLaunchType(LaunchType.FARGATE)
      .withNetworkConfiguration(netConfig)

    val result = client.runTask(rq)
    val failures = result.getFailures.asScala
    if(failures.length>1){
      logger.error(s"Failed to launch task: ${failures.head.getArn} ${failures.head.getReason}")
      Failure(new RuntimeException(failures.head.toString))
    } else {
      Success(result.getTasks.asScala.head)
    }
  }
}
