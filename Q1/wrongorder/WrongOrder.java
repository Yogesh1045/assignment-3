import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;

/** Q1 Student B: broader catch placed BEFORE a narrower one. This file is meant NOT to compile. */
public class WrongOrder {
    public static void main(String[] args) {
        try {
            new FileReader("missing.txt").close();
        } catch (IOException e) {                    // broader first ...
            System.out.println("IO problem");
        } catch (FileNotFoundException e) {          // ... makes this unreachable -> compile error
            System.out.println("file missing");
        }
    }
}
