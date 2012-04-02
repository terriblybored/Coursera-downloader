Coursera video downloader
=========================

Horribly rough - first project in Scala; doesn't accept nearly any input at all, or display download progress.

Downloads all videos in course in parallel. Requires username and password. Will not download if file of same size and name is found.

Run using 

  java -jar downloader.jar USERNAME PASSWORD COURSENAME

Coursename should be taken from the site url, for example,

  java -jar downloader.jar a@b.com abcd algo
  
  java -jar downloader.jar a@b.com abcd crypto

  
[Download executable](https://github.com/terriblybored/Coursera-downloader/raw/master/target/scala-2.9.1/default-317fdc_2.9.1-0.1-SNAPSHOT-one-jar.jar)

