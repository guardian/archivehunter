import React from 'react';
import PropTypes from 'prop-types';
import EntryView from './EntryView.jsx';
import Dialog from 'react-dialog';

class SearchResultsComponent extends React.Component {
    static propTypes = {
        entries: PropTypes.array.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            showingPreview: null
        };

        this.onItemOpen = this.onItemOpen.bind(this);
        this.onItemClose = this.onItemClose.bind(this);
    }

    onItemOpen(newTarget){
        this.setState({showingPreview: newTarget})
    }

    onItemClose(){
        this.setState({showingPreview: null});

    }

    render(){
        return <div className="search-results-container">
            {
                this.props.entries.map(entry=><EntryView entry={entry} itemOpenRequest={this.onItemOpen}/>)
            }
            {
                this.state.showingPreview && <Dialog
                    modal={true}
                    onClose={this.onItemClose}
                    closeOnEscape={true}
                    hasCloseIcon={true}
                    isDraggable={true}
                    position={{x: window.innerWidth/2-250, y:0}}
                    buttons={
                        [{
                            text: "Close",
                            onClick: () => this.onItemClose()
                        }]
                    }>
                    <EntryDetails entry={this.state.showingPreview}/>
                </Dialog>
            }
        </div>
    }
}

export default SearchResultsComponent;