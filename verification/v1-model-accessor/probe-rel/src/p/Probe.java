package p;
public class Probe {
    public static void main(String[] a) {
        System.out.println("无显式初始化器 : " + new Child().rel);
        System.out.println("有显式初始化器 : " + new ChildWithInit().rel);
    }
}
