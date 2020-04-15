import java.io.File
import java.nio.charset.Charset

import Bot.PerChatStarter
import BotUtils._
import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{
  ActorRef,
  ActorSystem,
  Behavior,
  SupervisorStrategy,
  Terminated
}
import com.bot4s.telegram.api.RequestHandler
import com.bot4s.telegram.models.{ChatId, User}
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.IOUtils
import slogging.{LogLevel, LoggerConfig, PrintLoggerFactory}
import sun.misc.{Signal, SignalHandler}

import scala.concurrent.Future
import scala.concurrent.duration._

object Ausweis extends App {

  val config = if (args.isEmpty) { ConfigFactory.load() }
  else {
    ConfigFactory
      .parseFile(new File(args(0)))
      .withFallback(ConfigFactory.load())
  }
  val ausweisConfig = config.getConfig("ausweis")

  LoggerConfig.factory = PrintLoggerFactory()
  LoggerConfig.level = LogLevel.INFO

  // Fill global configuration
  GlobalConfig.help = Some(
    IOUtils.resourceToString("/help.md", Charset.forName("UTF-8"))
  )
  GlobalConfig.privacyPolicy = Some(
    IOUtils
      .resourceToString("/privacy.md", Charset.forName("UTF-8"))
      .replaceAll("CONTACT-EMAIL", ausweisConfig.getString("contact-email"))
  )

  // Main actor guardian, with no command
  val mainBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
    // PDF builder actor
    val pdfBuilder = {
      val certificate = IOUtils.resourceToByteArray("/certificate.84dda806.pdf")
      val arialFont = IOUtils.resourceToByteArray("/arial.ttf")
      context.spawn(
        Behaviors
          .supervise(PDFBuilder.makeActor(certificate, arialFont))
          .onFailure[Throwable](SupervisorStrategy.restart),
        "PDF-builder"
      )
    }
    // CouchDB database actor
    val db = context.spawn(
      Behaviors
        .supervise(CouchDB(ausweisConfig.getString("database")))
        .onFailure[Throwable](SupervisorStrategy.restart),
      name = "CouchDB"
    )
    // Starter for new actors in charge of an individual conversation
    // with a Telegram user. Stop an actor after it has been idle for
    // 15 minutes. It may cause data loss if someone is entering their
    // information and makes a pause for 15 minutes right in the middle,
    // but this is supposed to be an interactive service.
    val perChatStarter: PerChatStarter = (
        user: User,
        client: RequestHandler[Future],
        parent: ActorRef[Bot.RequestChatShutdown],
        debugActor: Option[ActorRef[String]]
    ) =>
      ChatterBot(client, user, pdfBuilder, db, debugActor)
        .withIdleTimeout(
          15.minutes,
          parent,
          Bot.RequestChatShutdown(ChatId(user.id), "d'inactivité de votre part")
        )
    // During development, the debug actor can receive information when
    // data enters the database or when a certificate is sent.
    val debugActor = if (ausweisConfig.hasPath("debug")) {
      val ref = context.spawn(
        Behaviors
          .supervise(
            DebugBot(
              ausweisConfig.getString("debug.telegram-token"),
              ChatId(ausweisConfig.getInt("debug.chat-id"))
            )
          )
          .onFailure[Throwable](SupervisorStrategy.restart),
        "debug-bot"
      )
      Some(ref)
    } else {
      None
    }
    // Main bot. Since it might die if the connection with the Telegram servers is broken,
    // we restart it with an exponential backoff strategy in order not to hammer the servers
    // with repeated requests. In case of normal termination (for example after a global
    // shutdown request), terminate the system as well.
    val bot = {
      val botToken = ausweisConfig.getString("telegram-token")

      context.spawn(
        Behaviors
          .supervise(Bot(botToken, perChatStarter, debugActor))
          .onFailure[Throwable](
            SupervisorStrategy
              .restartWithBackoff(5.seconds, 30.seconds, 0.2)
              .withResetBackoffAfter(5.minutes)
          ),
        "ausweis-bot"
      )
    }
    context.watch(bot)

    // Catch SIGINT and SIGTERM, and start a proper shutdown procedure that
    // will warn users of an imminent shutdown if their unsaved personal data
    // will be lost.
    val signalHandler = new SignalHandler {
      override def handle(signal: Signal): Unit =
        bot ! Bot.InitiateGlobalShutdown
    }
    Signal.handle(new Signal("INT"), signalHandler)
    Signal.handle(new Signal("TERM"), signalHandler)

    Behaviors.receiveSignal {
      case (context, Terminated(_)) =>
        context.log.info("Main bot actor terminated, terminating ActorSystem")
        Behaviors.stopped
    }
  }

  ActorSystem(mainBehavior, "guardian")
}