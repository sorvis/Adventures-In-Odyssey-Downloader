using Odyssey_Downloader;
using Odyssey_Downloader.Model;
using SimpleFixture;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace OdysseyDownloader.Tests.FileReader
{
    public class TestFileIndexScenerio : IDisposable
    {
        private List<string> _filesCreated = new List<string>();
        private Fixture _fixture;
        private readonly string _folderName;

        public TestFileIndexScenerio()
        {
            Config = new Config();
            _folderName = Guid.NewGuid().ToString();
            Directory.CreateDirectory(_folderName);
            Config.FullPathToFiles = _folderName;
            _fixture = new Fixture();
        }

        public Config Config { get; set; }

        public IEnumerable<string> Titles { get; private set; }
        public List<int> EpisodeNumbers { get; } = new List<int>();

        public void V1CreateTestMp3Files()
        {
            Titles = new[] { "some title", "other neat show" };
            foreach (var title in Titles)
            {
                var titleWithSpacesReplaced = title.Replace(' ', '_');
                var episodeNumber = Convert.ToInt32(_fixture.Generate<uint>() / 2);
                EpisodeNumbers.Add(episodeNumber);
                createFile(titleWithSpacesReplaced, Convert.ToString(episodeNumber));
            }
        }

        public void V2CreateTestMp3Files()
        {
            Titles = new[] { "some title", "other neat show" };
            foreach (var title in Titles)
            {
                var titleWithSpacesReplaced = title.Replace(' ', '_');
                createFile(titleWithSpacesReplaced);
            }
        }

        public IEnumerable<string> GetFileNamesCreated()
        {
            return _filesCreated.Select(x => Path.GetFileName(x));
        }

        public void Dispose()
        {
            foreach (var file in _filesCreated)
            {
                File.Delete(Path.Combine(_folderName, file));
            }
            _filesCreated.Clear();
            var indexFullFileName = Config.GetIndexFilePath();
            if (File.Exists(indexFullFileName)) File.Delete(indexFullFileName);
            Directory.Delete(_folderName);
        }

        public void WriteIndex(string indexContents)
        {
            var indexPath = Config.GetIndexFilePath();
            File.WriteAllText(indexPath, indexContents);
        }

        private void createFile(string title, string number = null)
        {
            string fileName = string.Empty;
            if (number != null)
            {
                 fileName = $"{number}#-{title}.mp3";
            }
            else
            {
                fileName = $"{title}.mp3";
            }
            using (File.Create(Path.Combine(_folderName, fileName))) { }
            _filesCreated.Add(fileName);
        }

        internal AudioFile GenerateAudioFile()
        {
            return _fixture.Generate<AudioFile>();
        }
    }
}
