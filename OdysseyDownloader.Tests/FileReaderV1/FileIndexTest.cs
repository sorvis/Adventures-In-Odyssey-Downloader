using FluentAssertions;
using Odyssey_Downloader;
using SimpleFixture;
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using Xunit;

namespace OdysseyDownloader.FileReaderV1.Tests
{
    public class FileIndexTest: IDisposable
    {
        private readonly FileIndex it;
        private readonly TestScenerio _scenerio;

        public FileIndexTest()
        {
            it = new FileIndex();
            _scenerio = new TestScenerio();
        }

        public void Dispose()
        {
            _scenerio.Dispose();
        }

        [Fact]
        public void it_should_find_each_title()
        {
            var titles = new[] { "some title", "other neat show" };
            _scenerio.CreateTestMp3FilesByTitle(titles);
            var result = it.RebuildIndex(_scenerio.Config);
            result.Should().NotBeNull();
            var actualTitles = result.Select(x => x.Title);
            actualTitles.Should().BeEquivalentTo(titles);
        }

        class TestScenerio:IDisposable
        {
            private List<string> _filesCreated = new List<string>();
            private Fixture _fixture;

            public TestScenerio()
            {
                Config = new Config();
                Config.FullPathToFiles = ".";
                _fixture = new Fixture();
            }

            public Config Config { get; set; }

            public void CreateTestMp3FilesByTitle(params string[] titles )
            {
                foreach(var title in titles)
                {
                    var titleWithSpacesReplaced = title.Replace(' ', '_');
                    createFile(titleWithSpacesReplaced, Convert.ToString(_fixture.Generate<uint>()));
                }
            }

            public void Dispose()
            {
                foreach(var file in _filesCreated)
                {
                    File.Delete(file);
                }
                _filesCreated.Clear();
            }

            private void createFile(string title, string number)
            {
                var fileName = $"{number}#-{title}.mp3";
                using (File.Create(fileName)) { }
                _filesCreated.Add(fileName);
            }
        }
    }
}
