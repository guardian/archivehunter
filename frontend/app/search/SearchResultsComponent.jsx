import React from 'react';
import PropTypes from 'prop-types';
import EntryView from './EntryView.jsx';

class SearchResultsComponent extends React.Component {
    static propTypes = {
        entries: PropTypes.array.isRequired,
        onItemOpen: PropTypes.func.isRequired,
        onItemClose: PropTypes.func.isRequired,
        selectedEntry: PropTypes.object,
        cancelToken: PropTypes.object
    };

    constructor(props){
        super(props);

        this.state = {
            showingPreview: null
        };
    }

    componentWillUpdate(newProps,newState){
        console.log("SearchResultsComponent will update");
    }

    render(){
        return <div className="search-results-container">
            {
                this.props.entries.map(entry=><EntryView style={{cursor: "pointer"}}
                                                         isSelected={ this.props.selectedEntry ? this.props.selectedEntry.id===entry.id : false}
                                                         entry={entry}
                                                         cancelToken={this.props.cancelToken}
                                                         itemOpenRequest={this.props.onItemOpen}
                />)
            }
        </div>
    }
}

export default SearchResultsComponent;