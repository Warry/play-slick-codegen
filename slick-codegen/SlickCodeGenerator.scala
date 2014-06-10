import scala.slick.model.codegen.SourceCodeGenerator
import scala.slick.model.Model
import scala.slick.driver.H2Driver

object SlickCodeGenerator extends App{
  val jdbcDriver = "org.h2.Driver"
  val slickProfile = scala.slick.driver.H2Driver
  val url = s"jdbc:h2:mem:slick_codegen;init=runscript from 'conf/schema.sql'"

  val db = slickProfile.simple.Database.forURL(url,driver=jdbcDriver)

  import java.io.File
  val path = new File("app/models/auto_generated")
  scala.util.Try(path.listFiles().filter(_.getName.endsWith(".scala")) foreach { _.delete() })
  
  class SlickCodeGenerator(val model: Model) extends SourceCodeGenerator(model: Model){ gen =>
    override def code = {
      s"""
import views.html.helper._
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.Lang
import models._

trait Model[T]{
  def playForm: Form[T]
  trait Html{
    def allInputs(implicit handler: FieldConstructor, lang: Lang): Seq[play.twirl.api.HtmlFormat.Appendable]
  }
  trait Labels{
    def singular: String
    def plural: String
  }
  def html: Html
  def labels: Labels
}
      """.trim + "\n\n" + super.code
    }
    override def tableName = _ match {
      case "COMPANY" => "Companies"
      case n => n.toCamelCase+"s"
    }
    override def entityName = _.toCamelCase
    override def Table = new Table(_){
      override def autoIncLastAsOption = true
      override def TableValue = new TableValue{
        override def rawName = super.rawName.head.toString.toLowerCase + super.rawName.tail
      }
      override def code = {
        def input(c: Column) = s"""
def ${c.name}(implicit handler: FieldConstructor, lang: Lang) = inputText(playForm("${c.name}"), '_label -> labels.columns.${c.name})
          """.trim
        def formField(c: Column) = {
          val rawFieldType = c.rawType match {
            case "Int" => "number"
            case "String" => "nonEmptyText"
            case "java.sql.Date" => """sqlDate("yyyy-MM-dd")"""
          }
          val fieldType = if (c.fakeNullable || c.model.nullable) s"optional($rawFieldType)" else rawFieldType
          s"""
"${c.name}" -> $fieldType
          """.trim
        }

        def fieldLabel(c: Column) = s"""
def ${c.name}: String = "${c.model.name.replace("_"," ").toLowerCase.capitalize}"
          """.trim

        super.code ++ Seq(s"""
case class ${EntityType.name}Model(playForm: Form[${EntityType.name}]) extends Model[${EntityType.name}]{
  val html = new Html
  class Html extends super.Html{
    // ${model.foreignKeys.map(_.referencingColumns.head).toString}
    def allInputs(implicit handler: FieldConstructor, lang: Lang) = Seq(
      ${indent(indent(
          columns
            // not include auto inc columns
            .filterNot(_.autoInc)
            // not include foreign keys
            .filterNot(c => model.foreignKeys.map(_.referencingColumns.head.name) contains c.model.name)
            .map(_.name)
            .map("inputs."+_)
            .mkString(",\n")
      ))}
    )
    object inputs{
      ${indent(indent(indent(columns.map(input).mkString("\n"))))}
    }
  }
  val labels = new super.Labels{
    def singular = "${EntityType.name}".toLowerCase
    def plural   = "${TableClass.name}".toLowerCase
    object columns{
      ${indent(columns.map(fieldLabel).mkString("\n"))}
    }
  }

}
object ${EntityType.name}Model extends ${EntityType.name}Model(
  Form(
    mapping(
      ${indent(indent(indent(columns.map(formField).mkString(",\n"))))}
    )(${EntityType.name}.apply)(${EntityType.name}.unapply)
  )
)
        """.trim)
      }
    }
  }
  val codegen = new SlickCodeGenerator(db.withSession(s => H2Driver.createModel(s)))
  codegen.writeToFile(
    "scala.slick.driver.H2Driver",
    "app",
    "models.auto_generated",
    "Models",
    "Models.scala"
  )
}