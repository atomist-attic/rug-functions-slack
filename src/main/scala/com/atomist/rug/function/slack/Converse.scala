package com.atomist.rug.function.slack

import com.atomist.rug.spi.Handlers.Status
import com.atomist.rug.spi.annotation.{Parameter, RugFunction, Secret, Tag}
import com.atomist.rug.spi.{AnnotatedRugFunction, FunctionResponse, StringBodyOption}
import com.typesafe.scalalogging.LazyLogging
import com.ullink.slack.simpleslackapi.{SlackAttachment, SlackPreparedMessage, SlackSession}
import com.ullink.slack.simpleslackapi.events.{SlackMessagePosted, SlackMessageUpdated}
import com.ullink.slack.simpleslackapi.impl.{SlackChatConfiguration, SlackSessionFactory}
import com.ullink.slack.simpleslackapi.listeners.{SlackMessagePostedListener, SlackMessageUpdatedListener}
import scala.collection.JavaConversions._

/**
  * Fetch a slack message from a channel matching regular expressions
  */
class Converse
  extends AnnotatedRugFunction
    with LazyLogging {

  @RugFunction(name = "slack-converse", description = "Post a slack message and wait for a matching response",
    tags = Array(new Tag(name = "slack")))
  def invoke(@Parameter(name = "timeoutSeconds") timeout: Int,
             @Parameter(name = "channel") channelName: String,
             @Parameter(name = "matching") matching: String,
             @Parameter(name = "message") message: String,
             @Parameter(name = "fromUsername") from: String,
             @Parameter(name = "asUsername") as: String,
             @Secret(name = "token", path = "secret://team?path=slack/legacy-token") token: String): FunctionResponse = {

    logger.info(s"Invoking slack-converse with timeout '$timeout', channel '$channelName', matching '$matching'")

    val waiter: AnyRef = new Object()

    val regexp = matching.r

    val config = SlackChatConfiguration.getConfiguration.withName(as).withIcon("https://www.atomist.com/img/Atomist-Logo.png")
    try {
      val session = SlackSessionFactory.createWebSocketSlackSession(token)
      var resultMesg: SlackMessagePosted = null
      var lastMesg: SlackMessagePosted = null
      var matched: Boolean = false

      session.addMessageUpdatedListener(new SlackMessageUpdatedListener {
        override def onEvent(t: SlackMessageUpdated, session: SlackSession): Unit = {
          if (t.getChannel.getName == channelName) {
            t.getAttachments.toList.foreach{ a =>
              if (regexp.findFirstMatchIn(a.getText).nonEmpty || regexp.findFirstMatchIn(a.getPretext).nonEmpty) {
                session.addReactionToMessage(t.getChannel, t.getMessageTimestamp, "point_up")
                matched = true
                waiter.notifyAll()
              }
            }

          }
        }
      })
      session.addMessagePostedListener(new SlackMessagePostedListener() {
        override def onEvent(t: SlackMessagePosted, slackSession: SlackSession): Unit = {
          if (t.getChannel.getName == channelName
            && t.getSender.getUserName == from) {
            lastMesg = t
            if (regexp.findFirstMatchIn(t.getJsonSource.toJSONString).nonEmpty) {
              resultMesg = t
              session.addReactionToMessage(t.getChannel, resultMesg.getTimestamp, "thumbsup")
              waiter.notifyAll()
            }
          }
        }
      })
      session.connect()
      val channel = session.findChannelByName(channelName) //make sure bot is a member of the channel.
      session.sendMessage(channel, new SlackPreparedMessage.Builder().withMessage(message).build(), config)
      waiter.synchronized {
        waiter.wait(timeout * 1000)
      }


      (resultMesg, matched, lastMesg) match {
        case(o: SlackMessagePosted, _, _) =>
          FunctionResponse(Status.Success, Some(s"Successfully matched [$matching]"), Some(200), StringBodyOption(o.getJsonSource.toJSONString))
        case(_, true, _) =>
          FunctionResponse(Status.Success, Some(s"Successfully matched [$matching]"), Some(200))
        case _ =>
          val msg = s"Timed out after $timeout seconds"
          if(lastMesg != null){
            session.sendMessage(channel, new SlackPreparedMessage.Builder().withMessage(lastMesg.getJsonSource.toJSONString).build(),config)
          }
          FunctionResponse(Status.Failure, Some(msg), Some(500))
      }
    } catch {
      case e: Exception =>
        val msg = s"Failed to get slack message"
        logger.error(msg, e)
        FunctionResponse(Status.Failure, Some(msg), None, StringBodyOption(e.getMessage))
    }
  }
}
