# Sample codes
### Compile the codes
- Download protobuf binary (+3.3.0) from [google page](https://developers.google.com/protocol-buffers/docs/downloads), unzip it in any directory
- Setting up toolchains
    - Create a new file `toolchains.xml` in MAVEN_HOME directory (usually in `${user.home}/.m2/`)
        ```
        <toolchains xmlns="http://maven.apache.org/TOOLCHAINS/1.1.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
            xsi:schemaLocation="http://maven.apache.org/TOOLCHAINS/1.1.0 http://maven.apache.org/xsd/toolchains-1.1.0.xsd">
             
            <toolchain>
                <type>protobuf</type>
                <provides>
                    <version>3.3.0</version>
                </provides>
                <configuration>
                    <protocExecutable>INSERT_PATH_TO_PROTOBUF_BIANRY</protocExecutable>
                </configuration>
            </toolchain>
             
        </toolchains>
        ```
- Run the build with `mvn clean install`