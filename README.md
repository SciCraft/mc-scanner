# mc-scanner

Scan Minecraft worlds for certain blocks/items or
collect statistics about inventory contents

## Compiling
```shell
> ./gradlew build 
```

## Example Usage

### Searching for Sponges (in 1.12)
```shell
> java -jar mc-scanner-<version>.jar -i sponge -b 19 <world directory> sponges.zip
# ... 20s later ...
# 2671/2671 5.9GiB/5.9GiB 298.6MiB/s 30 results 
```

### Collecting Statistics
Into a directory:
```shell
> java -jar mc-scanner-<version.jar> --stats <world directory> result/
```

Into a zip file:
```shell
> java -jar mc-scanner-<version.jar> --stats <world directory> result.zip
```

Of only one region file:
```shell
> java -jar mc-scanner-<version>.jar --stats <world>/region/r.92.-83.mca quarry-items.zip
# 1/1 10.4MiB/10.4MiB 11.1MiB/s 51224 results
```