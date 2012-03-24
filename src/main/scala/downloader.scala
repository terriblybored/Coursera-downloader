import dispatch._
import dispatch.tagsoup.TagSoupHttp._
import scala.actors.Actor._
import java.io.{File, FileWriter, FileOutputStream}

object downloader {

  def main(args: Array[String]) {

    var username = "32fdsf@mailinator.com"
    var password = "32fdsf@mailinator.com"

    if (args.length >= 2) {
      username = args(0)
      password = args(1)
    }
    else println("downloader EMAIL PASSWORD")

    if (args.length == 3) {
       val custom = new Coursera {
        val email = args(0)
        val password = args(1)
        val courseFriendlyName = args(2)
        val coursename = args(2)
        login()
        }
        downloadAllMp4(custom)
    }
    else {
    actor { val modelThinking = new ModelThinking(username, password); downloadAllMp4(modelThinking) }
    actor { val saas = new SaaS(username, password); downloadAllMp4(saas) }
    actor { val nlp = new NLP(username, password); downloadAllMp4(nlp) } }
  }
  
  def downloadAllMp4(classObj: Coursera) {
    //val links = classObj.lessonList.flatMap(lesson => lesson.subLectures.flatMap(sublecture => sublecture.resources.filter(_.fileType=="mp4").flatMap(_.getLink)))
    collection.parallel.ForkJoinTasks.defaultForkJoinPool.setParallelism(30)
    val links = classObj.lessonList.flatMap(lesson => lesson.subLectures.flatMap(sublecture => sublecture.resources.flatMap(_.getLink)))
    links.par.foreach(_.checkAndDownload)
    println(classObj.courseFriendlyName + " Finished")
  }
}


class Lesson(val id: Int, val name: String, private val subLecturesList: Seq[Sublecture], var parent: Class = null) {
  def subLectures: Seq[Sublecture] = {
    subLecturesList.foreach(sublecture => sublecture.parent = this) //Bad workaround to get parent; will have problems if sublectures can belong to two lessons
    subLecturesList
  }
    override def toString = "%d - %s".format(id, name)
}

class Sublecture(val id: Int, val name: String, private val resourcesList: Seq[Resource], var parent: Lesson = null) {
  def resources: Seq[Resource] = {
    resourcesList.foreach(resource => resource.parent = this)
    resourcesList
  }
  override def toString = "%d - %s ".format(id, name) + resources.map(_.fileType).mkString(";")
}

class Resource(val link: String, var parent: Sublecture = null) {
  def fileType = link match {
    case x if link.contains("pptx") => "pptx"
    case x if link.contains("txt") => "txt"
    case x if link.contains("mp4") => "mp4"
    case x if link.contains("pdf") => "pdf"
    case x if link.contains("format=srt") => "srt"
    case _ => "unknown"
  }
  def getLink: Option[Link] = {
    try {
      val sublecture = parent
      val lesson = sublecture.parent
      val course = lesson.parent
      course.getLink(lesson.id, sublecture.id, this.fileType)
    }
    catch {
      case _ => None
    }
  }
}

object FileSizeIdenticalException extends Exception
case class Link(file: File, link: String, executor: HttpExecutor) {

  def checkAndDownload() {
    try{
    executor(url(link) >:+ {(headers,req) =>
      headers("content-length").headOption match {
        case Some(x) if x.toInt==file.length() => throw FileSizeIdenticalException  //file size identical
        case _ => req >> {input =>
          file.getParentFile().mkdirs()
          val out = new FileOutputStream(file)
          var buffer = new Array[Byte](512000)
          Iterator.continually(input.read(buffer)).takeWhile(_ != -1).foreach({x => out.write(buffer,0,x)})
          out.close()
        }
      }
    })}
    catch {
      case FileSizeIdenticalException => println("File skipped")
      case _ => println("Download Failed")
    }
  }

}

abstract class Class() {
  val http = new Http with thread.Safety {override def maxConnections = 1000}
  def getLink(lessonId: Int,  subLectureId: Int, filetype: String) : Option[Link]
}

abstract class Coursera() extends Class {
  val email: String
  val password: String

  val site = :/("www.coursera.org").secure
  val coursename: String
  val courseFriendlyName: String

  def login() {
    // Grabbing cookies - talk to the wall
    http(site / coursename / "auth/welcome" >|)
    http(site / coursename / "auth/auth_redirector?type=login" >|)

    //disable redirection to reveal location headers
    http.client.asInstanceOf[ConfiguredHttpClient].setRedirectStrategy(new org.apache.http.impl.client.DefaultRedirectStrategy {
      import org.apache.http.{HttpRequest,HttpResponse}
      import org.apache.http.protocol.HttpContext    
      override def isRedirected(req: HttpRequest, res: HttpResponse, ctx: HttpContext) = false
    })

    val payload: String = http.x(site / coursename / "auth/auth_redirector?type=login&subtype=normal" headers_> {
      a => {
        """(?<=payload=)[\w%]*""".r.findFirstIn(a("location").head) match {
          case Some(x) => Request.decode_%(x)
          case _ => {
            println("Error: failed to get payload value, terminating program/actor now"); exit()
          }
        }
      }
    }) toString

    //renable redirection; taken from dispatch's ConfiguredHttpClient
    http.client.asInstanceOf[ConfiguredHttpClient].setRedirectStrategy(new org.apache.http.impl.client.DefaultRedirectStrategy {
    import org.apache.http.{HttpRequest,HttpResponse}
    import org.apache.http.protocol.HttpContext
    import org.apache.http.HttpStatus._
    override def isRedirected(req: HttpRequest, res: HttpResponse, ctx: HttpContext) =
      (SC_MOVED_TEMPORARILY :: SC_MOVED_PERMANENTLY :: SC_TEMPORARY_REDIRECT :: 
       SC_SEE_OTHER :: Nil) contains res.getStatusLine.getStatusCode
    })

    val courseSite = http((:/("authentication.coursera.org") secure) / "auth/auth/normal/index.php"
      <<? List("payload" -> payload)
      << List(("email" -> email), ("password" -> password), ("login" -> "Login"))
      </> {
      html => (html \\ "a" \ "@href").head.text
    })

    println("Successfully logged into " + courseSite)
  }

  lazy val lessonList: Seq[Lesson] = {
    val unprocessedList = http(site / coursename / "lecture/index" </> {
      html =>
        (html \\ "div").filter(x => (x \ "@class").text == "item_list").head
    })
    val invalidChars = """[^\w\.-]""".r
    val lessonNames = unprocessedList \ "h3" map (_.text) map (x => invalidChars.replaceAllIn(x.trim, " "))
    val lessonContents = unprocessedList \ "ul"
    for (((lessonName, lessonContent), id) <- lessonNames zip lessonContents zipWithIndex) yield {
      val sublectures = for ((sublecture, idSubLec) <- (lessonContent \ "li") zipWithIndex) yield {
        val name = invalidChars.replaceAllIn((sublecture \ "a").head.text.trim, " ")
        val resources = (sublecture \ "div" \ "a" \\ "@href") map (x => new Resource(x text))
        new Sublecture(idSubLec, name, resources)
      }
      new Lesson(id, lessonName, sublectures, this)
    }
  }
  def getLink(lessonId: Int,  subLectureId: Int, filetype: String) = {
    try {
      val lesson = lessonList.filter(_.id == lessonId).head
      val sublecture: Sublecture = lesson.subLectures.filter(_.id == subLectureId).head
      val name = "%02d-%02d ".format(lessonId,subLectureId) + lesson.name + " - " + sublecture.name + "." + filetype
      Some(new Link(new File(courseFriendlyName + "/" + name), sublecture.resources.filter(_.fileType==filetype).head.link, http))
    }
    catch {
      case _ => None
    }
  }
  def printLessons() {
    for (lesson <- lessonList) {
      println(lesson.toString)
      for (subLecture <- lesson.subLectures)
        println(" " + subLecture.toString)
    }
  }
}

class ModelThinking(val email: String, val password: String) extends Coursera {
  val courseFriendlyName = "Model Thinking"
  val coursename = "modelthinking"
  login()
}

class SaaS(val email: String, val password: String) extends Coursera {
  val courseFriendlyName = "SaaS"
  val coursename = "saas"
  login()
}

class NLP(val email: String, val password: String) extends Coursera {
  val courseFriendlyName = "Natural Language Processing"
  val coursename = "nlp"
  login()
}
