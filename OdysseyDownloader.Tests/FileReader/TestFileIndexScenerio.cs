﻿using Odyssey_Downloader;
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

        public TestFileIndexScenerio()
        {
            Config = new Config();
            Config.FullPathToFiles = ".";
            _fixture = new Fixture();
        }

        public Config Config { get; set; }
        public IEnumerable<string> Titles { get; private set; }
        public List<int> EpisodeNumbers { get; } = new List<int>();

        public void CreateTestMp3Files()
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

        public IEnumerable<string> GetFileNamesCreated()
        {
            return _filesCreated.Select(x => Path.GetFileName(x));
        }

        public void Dispose()
        {
            foreach (var file in _filesCreated)
            {
                File.Delete(file);
            }
            _filesCreated.Clear();
            var indexFullFileName = Config.GetIndexFilePath();
            if (File.Exists(indexFullFileName)) File.Delete(indexFullFileName);
        }

        private void createFile(string title, string number)
        {
            var fileName = $"{number}#-{title}.mp3";
            using (File.Create(fileName)) { }
            _filesCreated.Add(fileName);
        }
    }
}
