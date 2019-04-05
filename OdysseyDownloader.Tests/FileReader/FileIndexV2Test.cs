﻿using Adventures_In_Odyssey_Downloader.FileReaderV2;
using FluentAssertions;
using Newtonsoft.Json;
using Odyssey_Downloader.Model;
using System;
using System.IO;
using System.Linq;
using Xunit;

namespace OdysseyDownloader.Tests.FileReader
{
    public class FileIndexV2Test : IDisposable
    {
        private readonly FileIndexV2 it;
        private readonly TestFileIndexScenerio _scenerio;

        public FileIndexV2Test()
        {
            _scenerio = new TestFileIndexScenerio();
            it = new FileIndexV2(_scenerio.Config);
            _scenerio.V2CreateTestMp3Files();
        }

        public void Dispose()
        {
            _scenerio.Dispose();
        }

        [Fact]
        public void it_should_find_each_title()
        {
            var result = it.RebuildIndex();
            var actualTitles = result.Select(x => x.Title);
            actualTitles.Should().BeEquivalentTo(_scenerio.Titles);
        }

        [Fact]
        public void it_should_find_each_file_name()
        {
            var result = it.RebuildIndex();
            var actual = result.Select(x => x.FileName);
            var expectedFileNames = _scenerio.GetFileNamesCreated();
            actual.Should().BeEquivalentTo(expectedFileNames);
        }

        [Fact]
        public void it_should_read_back_same_as_Rebuild_created()
        {
            var expected = it.RebuildIndex();
            var actual = it.ReadIndex();
            actual.Should().BeEquivalentTo(expected);
        }

        [Fact]
        public void it_should_know_if_index_file_exists()
        {
            var indexContents = new {
                Version = "V2"
            };
            _scenerio.WriteIndex(JsonConvert.SerializeObject(indexContents));
            it.IndexDetected().Should().BeTrue();
        }

        [Fact]
        public void it_should_should_write_index_with_version_V2()
        {
            it.RebuildIndex();
            var fileText = File.ReadAllText(_scenerio.Config.GetIndexFilePath());
            dynamic index = JsonConvert.DeserializeObject(fileText);
            ((string)index.Version).Should().Be("V2");
        }

        [Fact]
        public void it_should_know_if_index_file_does_not_exist()
        {
            it.IndexDetected().Should().BeFalse();
        }

        [Fact]
        public void it_should_know_if_index_file_is_in_unknown_format()
        {
            var indexFileName = _scenerio.Config.GetIndexFilePath();
            File.WriteAllText(indexFileName, Guid.NewGuid().ToString());
            it.IndexDetected().Should().BeFalse();
        }

        [Fact]
        public void it_should_create_new_index_on_WriteToIndex_if_none_exists()
        {
            var expected = _scenerio.GenerateAudioFile();
            it.WriteToIndex(expected);
            var actual = it.ReadIndex();
            actual.Should().BeEquivalentTo(new[] { expected });
        }

        [Fact]
        public void it_should_append_to_index_on_WriteToIndex_if_one_exists()
        {
            var addOnFile = _scenerio.GenerateAudioFile();
            var expected = it.RebuildIndex().Concat(new[] { addOnFile });
            it.WriteToIndex(addOnFile);
            var actual = it.ReadIndex();
            actual.Should().BeEquivalentTo(expected);
        }
    }
}
