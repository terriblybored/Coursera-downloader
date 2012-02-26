import dispatch._
import dispatch.tagsoup.TagSoupHttp._
import scala.actors.Actor._
import java.io.{File, FileWriter, FileOutputStream}

object downloader {

  def main(args: Array[String]) {

    var username = ""
    var password = ""

    if (args.length == 2) {
      username = args(0)
      password = args(1)
    }
    else println("downloader USERNAME PASSWORD")

    val modelThinking = new ModelThinking(username, password)
    downloadAllMp4(modelThinking)
    val saas = new SaaS(username, password)
    downloadAllMp4(saas)
  }
  
  def downloadAllMp4(classObj: Coursera) {
    val lessonsList = classObj.lessonList
    val lessonsLength = lessonsList.length
    (0 until lessonsLength).par.foreach(x => {
      val sublecturesLength = lessonsList(x).subLectures.length
      (0 until sublecturesLength).par.foreach(y => {classObj.getLink(x,y,"mp4").foreach(_.checkAndDownload())
      })
    })
  }
}

case class Lesson(id: Int, name: String, subLectures: Seq[Sublecture]) {
  override def toString = "%d - %s".format(id, name)
}

case class Sublecture(id: Int, name: String, resources: Seq[Resource]) {
  override def toString = "%d - %s ".format(id, name) + resources.map(_.fileType).mkString(";")
}

class Resource(val link: String) {
  def fileType = link match {
    case x if link.contains("pptx") => "pptx"
    case x if link.contains("txt") => "txt"
    case x if link.contains("mp4") => "mp4"
    case _ => "unknown"
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
  val http = new Http with thread.Safety
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

    val payload: String = http(site / coursename / "auth/auth_redirector?type=login&subtype=normal" >- {
      (a: String) => {
        """(?<=payload=)[\w%]*""".r.findFirstIn(a) match {
          case Some(x) => Request.decode_%(x)
          case _ => {
            println("Error: failed to get payload value, terminating program/actor now"); exit()
          }
        }
      }
    }) toString

    val courseSite = http((:/("authentication.campus-class.com") secure) / "auth/auth/normal/index.php"
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
      new Lesson(id, lessonName, sublectures)
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




