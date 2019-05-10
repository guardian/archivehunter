import Autocomplete from "react-autocomplete";
import React from "react";
import PropTypes from "prop-types";
import ClickableIcon from "./ClickableIcon.jsx";

/**
 * wraps the Autocomplete component to provide clickable enter/cancel buttons
 */
class AutocompletingEditBox extends React.Component {
    static propTypes = {
        items: PropTypes.array.isRequired,
        initialValue: PropTypes.string.isRequired,
        newValueOkayed: PropTypes.func.isRequired,
        filterOptions: PropTypes.boolean
    };

    constructor(props){
        super(props);

        this.state = {
            currentValue: props.initialValue ? props.initialValue : "",
            showButtons: false
        };

        this.cancelClicked = this.cancelClicked.bind(this);
    }

    filteredItems(){
        if(this.props.filterOptions)
            return this.props.items.filter(item=>item.startsWith(this.state.currentValue));
        else return this.props.items;
    }

    componentDidUpdate(prevProps, prevState, snapshot) {
        if(
            !this.state.showButtons && this.state.currentValue!==prevState.currentValue ||
            prevProps.initialValue!==this.props.initialValue
        )
            this.setState({showButtons: this.state.currentValue!==this.props.initialValue});
    }

    cancelClicked(){
        this.setState({currentValue: this.props.initialValue, showButtons:false});
    }

    render() {
        return <span><Autocomplete getItemValue={item=>item}
                              items={this.filteredItems()}
                              renderItem={(item, isHighlighted) =>
                                  <div style={{
                                      background: isHighlighted ? 'lightgray' : 'white',
                                      color: "black"
                                  }}>
                                      {item}
                                  </div>
                              }
                             value={this.state.currentValue}
                             onChange={evt=>this.setState({currentValue: evt.target.value})}
                             onSelect={val=>this.setState({currentValue: val})}
            />
            <span style={{display: this.state.showButtons ? "inline" : "none"}}>
                <ClickableIcon onClick={evt=>this.props.newValueOkayed(this.state.currentValue)} icon="check-circle"/>
                <ClickableIcon onClick={this.cancelClicked} icon="times-circle"/>
            </span>
        </span>
    }
}

export default AutocompletingEditBox;