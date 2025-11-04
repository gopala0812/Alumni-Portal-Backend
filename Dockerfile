FROM openjdk:17-jdk-slim
WORKDIR /app

# Copy dependencies
COPY lib/gson-2.10.1.jar ./lib/

# Create backend folder and copy files properly
RUN mkdir backend
COPY Server.java backend/
COPY Database.json .

# Compile
RUN javac -cp ".:lib/gson-2.10.1.jar" backend/Server.java

# Run the server
CMD ["java", "-cp", ".:lib/gson-2.10.1.jar", "backend.Server"]
