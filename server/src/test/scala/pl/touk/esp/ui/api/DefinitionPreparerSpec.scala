package pl.touk.esp.ui.api

import org.scalatest.{FlatSpec, Matchers}
import pl.touk.esp.ui.security.{LoggedUser, Permission}

class DefinitionPreparerSpec  extends FlatSpec with Matchers {

  it should "return objects sorted by label" in {

    val groups = DefinitionPreparer.prepareNodesToAdd(LoggedUser("aa", "", List(Permission.Admin), List()), ProcessTestData.processDefinition)

    groups.foreach { group =>
      group.possibleNodes.sortBy(_.label) shouldBe group.possibleNodes
    }
  }

}