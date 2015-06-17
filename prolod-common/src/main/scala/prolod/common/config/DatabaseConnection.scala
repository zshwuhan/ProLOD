package prolod.common.config

import java.io.File
import java.sql._
import java.util

import com.ibm.db2.jcc.am.{SqlException, SqlIntegrityConstraintViolationException, SqlSyntaxErrorException}
import com.typesafe.slick.driver.db2.DB2Driver.api._
import prolod.common.models._
import slick.jdbc.{GetResult, StaticQuery => Q}
import scala.collection.mutable
import scala.slick.jdbc.StaticQuery
import scala.concurrent.ExecutionContext.Implicits.global
import scala.slick.jdbc.JdbcBackend.Session
import scala.util.control.NonFatal
import scala.collection.JavaConverters._
import play.api.libs.json._
import prolod.common.models.PatternFormats.patternDBFormat
import prolod.common.models.{Dataset, Group, Pattern, PatternFromDB}
import prolod.common.models.EntityFormats.entityFormat
import prolod.common.models.EntityFormats.tripleFormat
import slick.profile.SqlStreamingAction

import scala.Function.tupled
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.Map
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.slick.jdbc.JdbcBackend.Session
import scala.io.Source

/*
case class Schemata(id : String, schema_name : String, entities : Int, tuples : Int)

class Schematas(tag: Tag)
extends Table[Schemata](tag, "PROLOD_MAIN.SCHEMATA") {

def id = column[String]("id", O.PrimaryKey)
def schema_name = column[String]("schema_name", O.NotNull)
def entities = column[Int]("entities", O.NotNull)
def tuples = column[Int]("tuples", O.NotNull)

def * = (id, schema_name, entities, tuples) <> (Schemata.tupled, Schemata.unapply)
}
*/

class DatabaseConnection(config : Configuration) {


	/*def insertStats(s: String, d: GraphLOD): Unit = {

	}
      */

	var driver = com.typesafe.slick.driver.db2.DB2Driver.api

	val url = "jdbc:db2://"+config.dbDb2Host+":"+config.dbDb2Port+"/"+config.dbDb2Database
	val username = config.dbDb2Username
	val password = config.dbDb2Password
	// Class.forName("com.typesafe.slick.driver.db2.DB2Driver")
	Class.forName("com.ibm.db2.jcc.DB2Driver")
	// DriverManager.getConnection(url, username, password)

	val db:Database = Database.forURL(url, username, password, driver="com.ibm.db2.jcc.DB2Driver")
	var connection:Connection = DriverManager.getConnection(url, username, password)

	/**
	 * execute sql and convert async result to sync - http://slick.typesafe.com/doc/3.0.0/sql.html
	 */
	def execute[T](implicit sql: SqlStreamingAction[Vector[T], T, Effect]): Vector[T] = {
		val q = db.run(sql)
		Await.result(q, Duration.Inf)
		val value = q.value.get
		value.get
	}

	def createTables(name : String): Unit = {
		try {
			var createStatement = connection.createStatement()
			var createResultSet = createStatement.execute("CREATE SCHEMA " + name)
		} catch {
			case e : SqlSyntaxErrorException => println(e.getMessage)
		}

		try {
			var createStatement = connection.createStatement()
			createStatement.execute("DROP TABLE "+name+".patterns")
			createStatement.close
		} catch {
			case e : SqlSyntaxErrorException => println(e.getMessage)
		}
		try {
			var createStatement = connection.createStatement()
			createStatement.execute("CREATE TABLE "+name+".patterns (id INT, pattern CLOB, occurences INT)")
			createStatement.close
		} catch {
			case e : SqlSyntaxErrorException => println(e.getMessage)
		}

		try {
			var createStatement = connection.createStatement()
			createStatement.execute("DROP TABLE "+name+".coloredpatterns")
			createStatement.close
		} catch {
			case e : SqlSyntaxErrorException => println(e.getMessage)
		}
		try {
			var createStatement = connection.createStatement()
			createStatement.execute("CREATE TABLE "+name+".coloredpatterns (id INT, pattern CLOB)")
			createStatement.close
		} catch {
			case e : SqlSyntaxErrorException => println(e.getMessage)
		}

		try {
			var createStatement = connection.createStatement()
			createStatement.execute("DROP TABLE "+name+".graphstatistics")
			createStatement.close
		} catch {
			case e : SqlSyntaxErrorException => println(e.getMessage)
		}
		try {
			var createStatement = connection.createStatement()
			var createResultSet = createStatement.execute("CREATE TABLE "+name+".graphstatistics (nodedegreedistribution CLOB, averagelinks FLOAT, edges INT, connectedcomponents INT, stronglyconnectedcomponents INT, gcnodes INT)")
		} catch {
			case e : SqlSyntaxErrorException => println(e.getMessage)
		}

		try {
			var createStatement = connection.createStatement()
			var createResultSet = createStatement.execute("DROP TABLE "+name+".CLUSTERS")
		} catch {
			case e : SqlSyntaxErrorException => println(e.getMessage)
		}

		try {
			var createStatement = connection.createStatement()
			var createResultSet = createStatement.execute("CREATE TABLE "+name+".CLUSTERS "+
				"(                                                                   "+
				"       ID INT NOT NULL GENERATED ALWAYS AS IDENTITY(START WITH 1 INCREMENT BY 1),    "+
				"USERNAME VARCHAR(50) DEFAULT 'default' NOT NULL,       "+
				//"SESSION_ID INT NOT NULL,                    "+
				//"SESSION_LOCAL_ID INT,                  "+
				"LABEL VARCHAR(255),                      "+
				//"CHILD_SESSION INT,                         "+
				//"AVG_ERROR FLOAT(53),                         "+
				"CLUSTER_SIZE INT,                              "+
				//"PARTITIONNAME VARCHAR(255) DEFAULT 'MAINTABLE', "+
				"PRIMARY KEY (ID, USERNAME)                                "+
				")")
		} catch {
			case e : SqlSyntaxErrorException => println(e.getMessage)
		}

		var sqlDir = new File("prolod-preprocessing/sql/")
		for (file <- sqlDir.listFiles) {
			try {
				val queryString = Source.fromFile(file.getPath).mkString
				val query = String.format(queryString, name)
				try {
					getTableNameFromStatement(queryString) match {
						case tableName => {
							try {
								var dropStatement = connection.createStatement()
								var tableNamenormalized = tableName
								if (tableName.equals("")) tableNamenormalized = file.getName.replace(".sql", "")
								dropStatement.execute("DROP TABLE " + name + "." + tableNamenormalized)
								dropStatement.close
							} catch {
								case e : SqlSyntaxErrorException => println(e.getMessage)
							}
						}
						//case "" => println(queryString)
					}
					val statement = connection.prepareStatement(query)
					statement.execute
					statement.close
				} catch {
					case e : SqlSyntaxErrorException => println(e.getMessage +  System.lineSeparator() + query)
				}

				    /*
				var createStatement = connection.createStatement()
				val statement = replaceDatasetName(queryString, name)
				getTableNameFromStatement(statement) match {
					case tableName => {
						try {
							var createStatement = connection.createStatement()
							var createResultSet = createStatement.execute("DROP TABLE " + replaceDatasetName(tableName, name))
						} catch {
							case e : SqlSyntaxErrorException => println(e.getMessage)
						}
					}
					case "" => println(statement)
				}
				var createResultSet = createStatement.execute(statement);
				*/
			} catch {
				case e : SqlSyntaxErrorException => println(e.getMessage)
			}
		}
	}

	def getTableNameFromStatement(s: String) : String = {
		val pattern = """%s.(.*)""".r

		s match {
			case pattern(group) => {
				println(group)
				group
			}
			case _ => {
				println("")
				""
			}
		}
		/*
		var result = pattern.findFirstIn(s).gro//for (m <- pattern findFirstIn s) yield m
		println(s + result)
		result                */
	}

	def getGroups(s: String, ontologyNamespace : String): Seq[Group] = {
		val table = s
		var sql = sql"SELECT label, cluster_size FROM #${table}.CLUSTERS WHERE username = 'ontology'".as[(String, Int)]
		var id : Int = -1
		try {
			val result = execute(sql)
			result map tupled((label, cluster_size) => {
				id += 1
				new Group(id, removeOntologyNamespace(label, ontologyNamespace), cluster_size)
			})
		} catch {
			case e : SqlSyntaxErrorException => println("This dataset has no clusters: " + s)
			Nil
		}
	}


	private def removeOntologyNamespace(name: String, ontologyNamespace: String): String = {
		var result = name
		if (ontologyNamespace != null) result = result.replace(ontologyNamespace, "")
		result
	}

	def getDatasetEntities(name : String) : Int = {
		val sql = sql"SELECT entities FROM PROLOD_MAIN.SCHEMATA WHERE id = ${name}".as[Int]
		execute(sql).headOption.getOrElse(-1)
	}

	def getDatasets(): Seq[Dataset] = {
		var datasets: List[Dataset] = Nil

		val sql = sql"SELECT id, schema_name, entities, ontology_namespace FROM PROLOD_MAIN.SCHEMATA".as[(String, String, Int, String)]

		val result = execute(sql) map tupled((id, schema, entities, ontology_namespace) => {
			new Dataset(id, schema, entities, getGroups(id, ontology_namespace))
		})
		result.filter(_.size > 0)
	}


	def getStatistics(s: String) : mutable.Map[String, String] = {
		var statistics = mutable.Map[String, String]()
		val statement = connection.createStatement()
		val resultSet = statement.executeQuery("SELECT nodedegreedistribution, averagelinks, edges, gcnodes, connectedcomponents, stronglyconnectedcomponents FROM " + s+ ".graphstatistics")
		while ( resultSet.next() ) {
			statistics += ("nodedegreedistribution" -> resultSet.getString("nodedegreedistribution"))
			statistics += ("averagelinks" -> resultSet.getString("averagelinks"))
			statistics += ("edges" -> resultSet.getString("edges"))
			statistics += ("gcnodes" -> resultSet.getString("gcnodes"))
			statistics += ("connectedcomponents" -> resultSet.getString("connectedcomponents"))
			statistics += ("stronglyconnectedcomponents" -> resultSet.getString("stronglyconnectedcomponents"))
		}
		statistics
	}

	def getColoredPatterns(s: String, id : Int): List[Pattern] = {
		var patterns : List[Pattern] = Nil
		try {
			val statement = connection.createStatement()
			val resultSet = statement.executeQuery("SELECT pattern FROM "+ s+".COLOREDPATTERNS WHERE id = "+id)
			while ( resultSet.next() ) {
				val pattern = resultSet.getString("pattern")
				// val occurences = resultSet.getInt("occurences")

				val patternJsonT = Json.parse(pattern).validate[PatternFromDB]
				val patternsV = List(patternJsonT).filter(p => p.isSuccess).map(p => p.get)
				val errors = List(patternJsonT).filter(p => p.isError)
				if (errors.nonEmpty) {
					println("Could not validate " + errors)
				}

				val patternJson = Json.parse(pattern).validate[PatternFromDB].get
				patterns :::= List(new Pattern(id, "", -1, patternJson.nodes, patternJson.links)) // new Pattern(id, "", occurences, Nil, Nil)
			}
		} catch {
			case e : SqlSyntaxErrorException => println("This dataset has no patterns: " + s)
		}
		patterns
	}

	def getPatterns(s: String): List[Pattern] = {
		var patterns : List[Pattern] = Nil
		try {
			val statement = connection.createStatement()
			val resultSet = statement.executeQuery("SELECT id, pattern, occurences FROM "+ s+".PATTERNS ORDER BY occurences ASC")
			while ( resultSet.next() ) {
				val id = resultSet.getInt("id")
				val pattern = resultSet.getString("pattern")
				val occurences = resultSet.getInt("occurences")
				val patternJson = Json.parse(pattern).validate[PatternFromDB].get
				patterns :::= List(new Pattern(id, "", occurences, patternJson.nodes, patternJson.links)) // new Pattern(id, "", occurences, Nil, Nil)
			}
		} catch {
			case e : SqlSyntaxErrorException => println("This dataset has no patterns: " + s)
		}
		patterns
	}

	def getEntityDetails(dataset: String, entity: String): Entity = {
		var triples : List[Triple] = Nil
		var subjectUri = ""
		var predicateUri = ""
		var objectUri = ""
		var subjectId = getSubjectId(dataset, entity)
		try {
			val statement = connection.createStatement()
			val resultSet = statement.executeQuery("SELECT tuple_id, predicate_id FROM "+ dataset+".maintable WHERE subject_id = "+subjectId)
			while ( resultSet.next() ) {
				val objectId = resultSet.getString("tuple_id")
				val predicateId = resultSet.getString("predicate_id")

				val statement3 = connection.createStatement()
				val resultSet3 = statement3.executeQuery("SELECT object FROM "+ dataset+".objecttable WHERE tuple_id = "+objectId)
				while ( resultSet3.next() ) {
					objectUri = resultSet3.getString("object")
				}
				statement3.close

				val statement2 = connection.createStatement()
				val resultSet2 = statement2.executeQuery("SELECT object FROM "+ dataset+".predicatetable WHERE id = "+predicateId)
				while ( resultSet2.next() ) {
					predicateUri = resultSet2.getString("predicate")
				}
				statement2.close

				val statement1 = connection.createStatement()
				val resultSet1 = statement1.executeQuery("SELECT subject FROM "+ dataset+".subjecttable WHERE id = "+subjectId)
				while ( resultSet2.next() ) {
					subjectUri = resultSet1.getString("subject")
				}
				statement1.close
				triples :::= List(new Triple(subjectUri, predicateUri, objectUri))
			}
			statement.close
		} catch {
			case e : SqlSyntaxErrorException => println(e.getMessage)
		}

		// TODO label
		var entityDetails = new Entity(entity, entity, triples)
		entityDetails
	}

	def insert: DBIO[Unit] = DBIO.seq(
		// sqlu"INSERT INTO PROLOD_MAIN.SCHEMATA ('ID', 'SCHEMA_NAME', 'TUPLES', 'ENTITIES') VALUES ('caterpillar','caterpillar',20,3)"
	)

	def replaceDatasetName(query: String, name: String) = {
		query.replace("##dataset##", name)
	}

	def insertDataset(name : String, tuples: Int, entities: Int, ontologyNamespace : String) {
		try {
			val statement = connection.createStatement()
			val resultSet = statement.execute("INSERT INTO PROLOD_MAIN.SCHEMATA (ID, SCHEMA_NAME, TUPLES, ENTITIES, ONTOLOGY_NAMESPACE) VALUES ('"+name+"','"+name+"',"+tuples+","+entities+",'"+ontologyNamespace+"')")
		} catch {
			case e : SqlIntegrityConstraintViolationException => println("Dataset already exists")
		}



			/*
					db withSession((session: Session) => {
						(sql"""INSERT INTO PROLOD_MAIN.SCHEMATA (ID, SCHEMA_NAME, TUPLES, ENTITIES) VALUES ('caterpillar','caterpillar',20,3)""")
					}
					*/

		     /*
		db withSession {
			val schemata = TableQuery[Schematas]
			schemata.insertStatement
			var res1: String = "insert into PROLOD_MAIN.SCHEMATA ('ID', 'SCHEMA_NAME', 'TUPLES', 'ENTITIES') values (?,?,?,?)"
			schemata += Schemata("caterpillar","caterpillar",20,3)
			implicit session => schemata.run
		}
		*/


		/*

  val plainQuery = sql"INSERT INTO PROLOD_MAIN.SCHEMATA ('ID', 'SCHEMA_NAME', 'TUPLES', 'ENTITIES') VALUES ('caterpillar','caterpillar',20,3)"

  println("Generated SQL for plain query:\n" + plainQuery.getStatement)

  // Execute the query
  println(plainQuery.list)
          */

		/*
db withSession { implicit sess =>
val st = sess.createStatement()
st.execute("INSERT INTO PROLOD_MAIN.SCHEMATA ('ID', 'SCHEMA_NAME', 'TUPLES', 'ENTITIES') VALUES ('caterpillar','caterpillar',20,3)")

}
       */


		// sql"INSERT INTO PROLOD_MAIN.SCHEMATA ('ID', 'SCHEMA_NAME', 'TUPLES', 'ENTITIES') VALUES ('caterpillar','caterpillar','20','3')"


		/*
		implicit val getSupplierResult = GetResult(r => r.nextString)
		System.out.println(getSupplierResult)
          */
	}

	def insertPatterns(name: String, patterns: util.HashMap[Integer, util.HashMap[String, Integer]], coloredPatterns: util.HashMap[Integer, util.List[String]]) {
		val coloredPatternsMap = coloredPatterns.asScala.toMap
		val patternsMap = patterns.asScala.toMap
		patternsMap.foreach {
			case (id, patternHashMap) => {
				patternHashMap.asScala.toMap.foreach {
					case (pattern, occurences) => {
						try {
							val statement = connection.createStatement()
							val resultSet = statement.execute("INSERT INTO " + name + ".PATTERNS (ID, PATTERN, OCCURENCES) VALUES (" + id + ", '" + pattern + "'," + occurences + ")")
						} catch {
							case e: SqlIntegrityConstraintViolationException => println("Pattern already exists")
							case e: SqlException => println(e.getMessage)
							case e: SqlSyntaxErrorException => println(e.getMessage + System.lineSeparator() + "INSERT INTO " + name + ".PATTERNS (ID, PATTERN, OCCURENCES) VALUES (" + id + ", '" + pattern + "'," + occurences + ")")
						}
						val cPattern = coloredPatternsMap.get(id).get.asScala.toList
						cPattern.foreach { case (coloredpattern) =>
							try {
								val statement = connection.createStatement()
								val resultSet = statement.execute("INSERT INTO " + name + ".coloredpatterns (ID, PATTERN) VALUES (" + id + ", '" + coloredpattern + "')")
								// println(pattern)
							} catch {
								case e: SqlException => {
									println(e.getMessage)
									println(coloredpattern)
								}
							}
						}
					}
				}

			}
		}
	}

	class RsIterator(rs: ResultSet) extends Iterator[ResultSet] {
		def hasNext: Boolean = rs.next()
		def next(): ResultSet = rs
	}

	def performInsert(table: String, names: Seq[Any], values: Seq[Any]): Option[Int] = {
		val query = String.format("insert into %s (%s) values (%s)",
			table,
			commaize(names.map(n => n.toString).toList),
			commaize(values.map(v => "'" + v.toString.replace("'", "") + "'").toList)
		)
		try {
			val statement = connection.prepareStatement(query)
			statement.execute
			val key = statement.getGeneratedKeys
			statement.close
			if(key.next) {
				key.getInt(1)
			}
		} catch {
			case e : SqlIntegrityConstraintViolationException => println(e.getMessage +  System.lineSeparator() + query)
			case e : SqlSyntaxErrorException => println(e.getMessage +  System.lineSeparator() + query)
		}
		None
	}

	private def commaize(list: List[_ <: Any]): String = list match {
		case List()  => ""
		case List(x) => x.toString
		case _       => list(0) + ", " + commaize(list.tail)
	}

	def insertSubject(name: String, s: String): Int = {
		performInsert(name + ".subjecttable", List("subject"), List(s)) match {
			case Some(i) => i
			case None => {
				getSubjectId(name, s)
			}
		}
	}

	def getSubjectId(name: String, s: String): Int = {
		var result : Int = -1
		val statement = connection.createStatement()
		val resultSet = statement.executeQuery("SELECT id FROM " + name + ".subjecttable WHERE subject='" + s + "'")
		resultSet.next()
		result = resultSet.getInt("id")
		statement.close
		result
	}

	def insertObject(name: String, s: String): Int = {
		performInsert(name + ".objecttable", List("object"), List(s)) match {
			case Some(i) => i
			case None => {
				getObjectId(name, s)
			}
		}
	}

	def getObjectId(name: String, s: String): Int = {
		var result : Int = -1
		val statement = connection.createStatement()
		val resultSet = statement.executeQuery("SELECT tuple_id FROM " + name + ".objecttable WHERE object='" + s + "'")
		resultSet.next()
		result = resultSet.getInt("tuple_id")
		statement.close
		result
	}

	def insertPredicate(name: String, s: String): Int = {
		performInsert(name + ".predicatetable", List("predicate"), List(s)) match {
			case Some(i) => i
			case None => {
				getPredicateId(name, s)
			}
		}
	}

	def getPredicateId(name: String, s: String): Int = {
		var result : Int = -1
		val statement = connection.createStatement()
		val resultSet = statement.executeQuery("SELECT id FROM " + name + ".predicatetable WHERE predicate='" + s + "'")
		resultSet.next()
		result = resultSet.getInt("id")
		statement.close
		result
	}

	def insertTriples(name: String, s: Int, p: Int, o: Int): Unit = {
		performInsert(name + ".maintable", List("subject_id", "predicate_id", "tuple_id"), List(s, p, o))
		/*
		try {
			val statement = connection.createStatement()
			val resultSet = statement.execute("INSERT INTO " + name + ".subjecttable (subject) VALUES ('" + s + "')")
			println(resultSet)
		} catch {
			case e: SqlIntegrityConstraintViolationException => println(e.getMessage)
			case e: SqlException => println(e.getMessage)
		}
		*/
	}

	def insertStatistics(name: String, nodes: String, links: Double, edges: Int, gcNodes : Int, connectedcomponents : Int, stronglyconnectedcomponents : Int) = {
		try {
			val statement = connection.createStatement()
			val resultSet = statement.execute("INSERT INTO " + name + ".graphstatistics (nodedegreedistribution, averagelinks, edges, gcnodes, connectedcomponents, stronglyconnectedcomponents) VALUES ('" + nodes + "'," + links + ", " + edges + ", " + gcNodes + ", " + connectedcomponents + ", " + stronglyconnectedcomponents + ")")
		} catch {
			case e: SqlIntegrityConstraintViolationException => println(e.getMessage)
			case e: SqlException => println(e.getMessage)
		}
	}

	def insertClasses(name: String, clusters: util.List[String]) = {
		var clusterUris = clusters.asScala.toList
		clusterUris.foreach {
			case (cluster) =>
				try {
					val statement = connection.createStatement()
					val resultSet = statement.execute("INSERT INTO " + name + ".clusters (label, cluster_size, username) VALUES ('" + cluster + "', 0 , 'ontology')")
				} catch {
					case e: SqlIntegrityConstraintViolationException => println(e.getMessage + System.lineSeparator() + "INSERT INTO " + name + ".clusters (label, cluster_size, username) VALUES ('" + cluster + "', 0 , 'ontology')")
					case e: SqlException => println(e.getMessage + System.lineSeparator() + "INSERT INTO " + name + ".clusters (label, cluster_size, username) VALUES ('" + cluster + "', 0 , 'ontology')")
					case e: SqlSyntaxErrorException   => println(e.getMessage + System.lineSeparator() + "INSERT INTO " + name + ".clusters (label, cluster_size, username) VALUES ('" + cluster + "', 0 , 'ontology')")
				}
		}

	}
}
