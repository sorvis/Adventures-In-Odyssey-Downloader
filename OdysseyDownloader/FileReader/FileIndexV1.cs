using NLog;
using Odyssey_Downloader;
using Odyssey_Downloader.Model;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace OdysseyDownloader.FileReader
{
    public class FileIndexV1 : IIndexReader
    {
        private readonly Logger _logger = LogManager.GetCurrentClassLogger();
        private string _fullPath;
        private string _fileExtension;
        private string _fullPathToIndex;
        private readonly Config _settings;

        public FileIndexV1(Config settings)
        {
            _fullPath = settings.FullPathToFiles;
            _fileExtension = settings.FileExtension;
            _fullPathToIndex = settings.GetIndexFilePath();
            _settings = settings;
        }

        public IEnumerable<AudioFile> RebuildIndex()
        {
            List<AudioFile> audioFiles = new List<AudioFile>();

            List<string> titleList = new List<string>();
            foreach (string file in getFileNamesInDir())
            {
                var justFileName = Path.GetFileName(file);
                var title = findElement(justFileName, _fileExtension, "#-").Replace("_", " ");
                var episodeNumber = findElement(justFileName, "#-", "/");
                var audioFile = new AudioFile
                {
                    Title = title,
                    FileName = justFileName,
                    Number = episodeNumber,
                    Date = File.GetCreationTime(file).ToString(),
                };
                audioFiles.Add(audioFile);
                titleList.Add(formatIndexLine(audioFile));
            }

            writeListToFile(titleList);
            return audioFiles;
        }

        private string formatIndexLine(AudioFile file)
        {
            return $"Episode {file.Number}: {file.Title} Date: {file.Date}";
        }

        public bool IndexDetected()
        {
            if (!File.Exists(_fullPathToIndex)) return false;
            try
            {
                var files = ReadIndex();
                return files.Any();
            }
            catch(Exception exception)
            {
                _logger.Error(exception, 
                    $"Unable to read contents of index file at: {_fullPathToIndex}. Probably this is the an older index reader checking for compatibility.");
                return false;
            }
        }

        public IEnumerable<AudioFile> ReadIndex()
        {
            // format of lines look like this: "Episode {episodeNumber}: {title}"
            foreach(var line in File.ReadAllLines(_fullPathToIndex))
            {
                var stripEpisode = line.Replace("Episode ", string.Empty);
                var splitOnColon = stripEpisode.Split(':', 2);
                var episodeNumber = splitOnColon.First();
                var titleAndDate = splitOnColon.Skip(1).First().Trim().Split("Date: ", 2);
                var title = titleAndDate.First().Trim();
                var date = titleAndDate.Skip(1).FirstOrDefault().Trim();
                yield return new AudioFile {
                    Number = episodeNumber,
                    Title = title,
                    FileName = $"{episodeNumber}#-{title.Replace(' ', '_')}{_fileExtension}",
                    Date = date,
                };
            }
        }

        public void WriteToIndex(AudioFile fileInfo)
        {
            var line = formatIndexLine(fileInfo);
            writeListToFile(new[] { line });
        }

        private static string reverseString(string text)
        {
            char[] array = text.ToCharArray();
            Array.Reverse(array);
            return new string(array);
        }

        private void checkForIndexFile()
        {
            try
            {
                StreamReader file = new StreamReader(_fullPathToIndex);
                file.Close();
            }
            catch
            {
                File.Create(_fullPathToIndex).Dispose();
                Console.WriteLine("Found no index file so rebuilding it.");
                RebuildIndex();
            }
        }

        private string findElement(string Source, string elementEnd, string elementStart)
        {
            int extensionIndex = Source.IndexOf(elementEnd);
            Source = Source.Substring(0, extensionIndex); //cut off source after file
            Source = reverseString(Source); // reverse the source so that the file url is first
            int httpIndex = Source.IndexOf(reverseString(elementStart));
            if (httpIndex > 0)
            {
                Source = Source.Substring(0, httpIndex); //cut off source before file
            }

            Source = reverseString(Source); // reverse the URL back to normal

            return Source;
        }

        private List<string> getFileNamesInDir()
        {
            var dir = _fullPath;
            var extension = _fileExtension;
            List<string> list = new List<string>();

            // Process the list of files found in the directory.
            foreach (string fileName in Directory.GetFiles(dir))
            {
                // do something with fileName
                if (fileName.Contains(extension))
                {
                    list.Add(fileName);
                }
            }
            list.Sort();
            return list;
        }

        private void writeListToFile(IEnumerable<string> fileLines)
        {
            var existingIndexFiles = new List<string>();
            if (IndexDetected())
            {
                existingIndexFiles.AddRange(ReadIndex().Select(formatIndexLine));
            }
            var filesToWrite = fileLines.Concat(existingIndexFiles).OrderBy(x => x);

            TextWriter newFile = new StreamWriter(_fullPathToIndex);
            foreach (string Currentline in filesToWrite)
            {
                newFile.WriteLine(Currentline);
            }
            // close the stream
            newFile.Close();
        }
    }
}