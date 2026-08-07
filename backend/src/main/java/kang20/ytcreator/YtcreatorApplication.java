package kang20.ytcreator;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.modulith.Modulithic;

@Modulithic(systemName = "ytcreator")
@SpringBootApplication
public class YtcreatorApplication {

	public static void main(String[] args) {
		SpringApplication.run(YtcreatorApplication.class, args);
	}
}
