package play.test.enhancer;

public class ComplexBean {
    public String existingGetter;

    public String getExistingGetter() {
        return existingGetter;
    }

    public String existingSetter;

    public void setExistingSetter(String value) {
        this.existingSetter = value;
    }

    public String differentTypeSetter;

    public void setDifferentTypeSetter(int value) {
        this.differentTypeSetter = Integer.toString(value);
    }

    public String multipleSetters;

    public void setMultipleSetters(int value) {
        this.multipleSetters = Integer.toString(value);
    }

    public void setMultipleSetters(String value) {
        this.multipleSetters = value;
    }
}