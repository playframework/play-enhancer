package play.test.enhancer;

public class MyBeanAccessor {
    public static String access(MyBean myBean) {
        return myBean.prop;
    }
}