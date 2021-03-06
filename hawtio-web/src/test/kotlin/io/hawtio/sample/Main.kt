package io.hawt.sample

import java.io.File
import java.lang.management.ManagementFactory
import org.eclipse.jetty.jmx.MBeanContainer
import org.eclipse.jetty.server.Server
import org.eclipse.jetty.util.log.Log
import org.eclipse.jetty.util.log.Slf4jLog
import org.eclipse.jetty.webapp.*
import org.mortbay.jetty.plugin.JettyWebAppContext
import org.slf4j.LoggerFactory
import org.springframework.context.support.ClassPathXmlApplicationContext
import org.fusesource.fabric.service.FabricServiceImpl
import org.fusesource.fabric.zookeeper.spring.ZKClientFactoryBean

/**
* Returns true if the file exists
*/
fun fileExists(path: String): Boolean {
    val file = File(path)
    return file.exists() && file.isFile()
}

/**
 * Returns true if the directory exists
 */
fun directoryExists(path: String): Boolean {
    val file = File(path)
    return file.exists() && file.isDirectory()
}

/**
 * Runs the web app
 */
fun main(args: Array<String>): Unit {
    val LOG = LoggerFactory.getLogger(javaClass());

    try {
        System.setProperty("org.eclipse.jetty.util.log.class", javaClass<Slf4jLog>().getName());
        Log.setLog(Slf4jLog("jetty"));
        val port = Integer.parseInt(System.getProperty("jettyPort", "8080"))
        val contextPath = System.getProperty("context", "/sample")
        var path = System.getProperty("webapp-outdir", "src/main/webapp")
        val webXml = path + "/WEB-INF/web.xml"
        require(fileExists(webXml), "No web.xml could be found for $webXml")

        println("Connect via http://localhost:$port$contextPath using web app source path: $path")

        /** Returns true if we should scan this lib for annotations */
        fun isScannedWebInfLib(path: String): Boolean {
            return path.endsWith("kool/website/target/classes")
            //return path.contains("kool")
            //return true
        }

        val pathSeparator = File.pathSeparator ?: ":"

        val classpath = System.getProperty("java.class.path") ?: ""
        val classpaths = classpath.split(pathSeparator)
        val jarNames: Collection<String> = classpaths.filter{ isScannedWebInfLib(it) }

        // TODO remove the File? stuff and null checks when issue fixed:
        val files = jarNames.map<String, File?>{ File(it) }.filter{ it != null && it.exists() }

        // TODO remove .toList() when filter() returns List
        val jars = files.filter{ it != null && it.isFile() }.toSet()
        val extraClassDirs = files.filter{ it != null && it.isDirectory() }.toList()

        println("Using WEB-INF/lib jars: $jars and classes dirs: $extraClassDirs")

        val context = JettyWebAppContext()
        context.setWebInfLib(jars.toList())
        context.setConfigurations(array(
                WebXmlConfiguration(),
                WebInfConfiguration()
        ))

        context.setDescriptor(webXml)
        context.setResourceBase(path)
        context.setContextPath(contextPath)
        context.setParentLoaderPriority(true)

        val server = Server(port)
        server.setHandler(context)

        // enable JMX
        val mbeanServer = ManagementFactory.getPlatformMBeanServer()
        var mbeanContainer = MBeanContainer(mbeanServer)
        server.getContainer()?.addEventListener(mbeanContainer);
        server.addBean(mbeanContainer)

        if (args.size == 0 || args[0] != "nospring") {
            // now lets startup a spring application context
            LOG.info("starting spring application context")
            val appContext = ClassPathXmlApplicationContext("applicationContext.xml")
            val activemq = appContext.getBean("activemq")
            LOG.info("created activemq: " + activemq)
            appContext.start()

            val logQuery = appContext.getBean("logQuery")
            LOG.info("created logQuery: " + logQuery)

            LOG.warn("Don't run with scissors!")
            LOG.error("Someone somewhere is not using Fuse! :)")
        }

        // lets connect to fabric
        val fabricUrl = System.getProperty("fabricUrl", "")
        val fabricPassword = System.getProperty("fabricPassword", "admin")

        if (fabricUrl != null && fabricUrl.length() > 0) {
            LOG.info("Connecting to Fuse Fabric at $fabricUrl")
            var factory = ZKClientFactoryBean()
            factory.setPassword(fabricPassword)
            factory.setConnectString(fabricUrl)
            var zooKeeper = factory.getObject()
            var impl = FabricServiceImpl()
            impl.setMbeanServer(mbeanServer)
            impl.setZooKeeper(zooKeeper)
            impl.init()
        }
        LOG.info("starting jetty")
        server.start()

        val contextLogger = context.getLogger()
        LOG.info("Jetty context has logger ${contextLogger} with class ${contextLogger.javaClass}")
        server.join()
    } catch (e: Throwable) {
        LOG.error(e.getMessage(), e)
    }
}
