/*
 * Copyright 2014 YMC. See LICENSE for details.
 */

import models.hbase.HBaseContext
import play.api._
import actors.UpdateMetricsActor

object Global extends GlobalSettings {

  private val apiVersions = List(
    "models.hbase096.HBaseContext096",
    "models.hbase092.HBaseContext092",
    "models.hbase090.HBaseContext090"
  );

  override def onStart(app: Application) {
    apiVersions.foreach { hbaseContext:String =>
      if(globals.hBaseContext == null) {
        try {
          Logger.debug("Try to intanciate api-wrapper %s".format(hbaseContext));
          globals.hBaseContext = Class.forName(hbaseContext).newInstance.asInstanceOf[HBaseContext]
        } catch {
          case e: java.lang.ClassNotFoundException =>
            Logger.debug("Instanciating api-wrapper %s failed ".format(hbaseContext));
        }
      }
    }
    if(globals.hBaseContext == null) {
      Logger.error("Could not instanciate any api wrapper, Hannibal will now exit");
      System.exit(1);
    }

    val role = Play.current.configuration.getString("ha.role").get
    Logger.info("CURRENT HA MODE: " + role);
    if (app.mode != Mode.Test && role == "active") {
      Logger.info("Application has started in " + app.mode + "-Mode " + role + " role with " + globals.hBaseContext.toString + ", starting Update-Metrics-Actor")
      UpdateMetricsActor.initialize( app.configuration )

    } else {
      Logger.info("Application has started in " + app.mode + "-Mode " + role + " role, do not start Update-Metrics-Actor")
    }
  }

  override def onStop(app: Application) {
    Logger.info("Application shutdown...")
  }
}

package object globals {
  var hBaseContext: HBaseContext = null
}
