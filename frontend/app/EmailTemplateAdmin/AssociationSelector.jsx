import React from "react";
import axios from "axios";
import PropTypes from "prop-types";
import GenericDropdown from "../common/GenericDropdown.jsx";
import ClickableIcon from "../common/ClickableIcon.jsx";

class AssociationSelector extends React.Component {
    static propTypes = {
        value: PropTypes.string,
        onChange: PropTypes.func.isRequired,
        forTemplate: PropTypes.string.isRequired
    };

    constructor(props){
        super(props);

        this.state = {
            editing: false
        }
    }

    static valueList = [
        "AdminPendingNotification",
        "UserRequestGrantedNotification",
        "UserRequestRejectedNotification",
        "UserMediaReadyNotification"
    ];

    render(){
        return <span>
            {
                this.state.editing ?
                    <GenericDropdown valueList={AssociationSelector.valueList}
                                     onChange={evt=>{
                                         const newValue = evt.target.value;
                                         this.setState({editing: false}, ()=>this.props.onChange(newValue, this.props.forTemplate));
                                     }}
                                     value={this.props.value ? this.props.value : ""}
                                     includeNull={true}/> :
                    <i>{this.props.value ? this.props.value : "(none)"}</i>
            }
            <ClickableIcon style={{width: "2em"}} onClick={evt=>this.setState({editing:true})} icon="edit"/>
        </span>
    }
}

export default AssociationSelector;