package test

import play.test.enhancer._

object Test extends App {

  println("First check that the accessors have been generated, the code below won't compile if they weren't.")
  val myBean = new MyBean
  myBean.setProp("foo")
  assert(myBean.getProp == "foo")

  myBean.setIsAdmin(true)
  assert(myBean.isAdmin)

  myBean.setB(true)
  assert(myBean.isB)

  myBean.setB(false)
  assert(!myBean.getB)

  myBean.setCanDeleteAccount(true)
  assert(myBean.canDeleteAccount)

  myBean.setCanDeleteAccount(true)
  assert(myBean.getCanDeleteAccount)

  println("Now check that accessors have been rewritten. If they haven't been, then the accessor will go directly to the field, rather than getting our overridden value")
  class OverridingBean extends MyBean {
    override def getProp = "bar"
  }
  val overridingBean = new OverridingBean
  overridingBean.prop = "foo"
  assert(MyBeanAccessor.access(overridingBean) == "bar")

  println("All tests passed!")
}
